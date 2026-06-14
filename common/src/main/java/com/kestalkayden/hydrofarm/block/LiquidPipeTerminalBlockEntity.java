package com.kestalkayden.hydrofarm.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.FluidApi;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;
import com.kestalkayden.hydrofarm.util.Backoff;

/** Server-tick driver for a liquid pipe terminal — the fluid pump replacement. The fluid twin of
 *  {@link ItemPipeTerminalBlockEntity}: each {@link PipeFace#EXTRACT} face moves fluid from its
 *  facing source, BFS-walks the connected pipe graph for every INSERT face network-wide, and
 *  distributes across them, both ends enforcing a fluid filter. Unlike items, fluid moves whole-mB
 *  each tick (no fractional buffer). Mirrors the old {@code LiquidPumpBlockEntity}, generalised from
 *  one-pump-one-face to many-faces-per-node. */
public class LiquidPipeTerminalBlockEntity extends BlockEntity {

    public static final int FILTER_SIZE = 5;
    /** Flattened single-tier throughput (mB/tick). 3/tick = 60 mB/s — the deliberate "middle"
     *  between the old standard (1) and advanced (5) liquid pumps. */
    public static final int THROUGHPUT_MB_PER_TICK = 3;
    /** Perf batch: accumulate throughput and only run the find()/BFS/distribute path once the buffer
     *  reaches this, so a steady source isn't network-walked every tick (the item terminal gets the
     *  same batching for free from its 1-item granularity). Net throughput is unchanged — the burst
     *  moves the whole accumulated budget. 12 mB ≈ one run per 4 ticks at 3 mB/tick. */
    private static final int BURST_THRESHOLD_MB = 12;
    /** Cap on unspent (positive) budget so a stalled face (full/absent targets) doesn't accumulate
     *  forever. Note {@code bufferMb} can also go NEGATIVE: a coarse-source pull (see {@link
     *  #coarsePull}) moves up to a whole bucket and debits it here, running the budget into debt
     *  (floor ≈ {@code BURST_THRESHOLD_MB - 1000} ≈ -988) that the per-tick accrual repays. */
    private static final int BUFFER_CAP_MB = 120;

    /** Adaptive back-off: an EXTRACT face that moves nothing (empty source / no INSERT targets / all
     *  destinations full) ramps its re-probe interval 10→20→40 ticks instead of bursting every gated
     *  tick. Reset on any successful move or a network topology change. */
    private static final int BACKOFF_BASE_COOLDOWN = 10;
    private static final int BACKOFF_MAX_COOLDOWN  = 40;
    private static final int BACKOFF_RAMP_CAP      = 2;

    /** Cached network-wide INSERT faces, shared across this terminal's EXTRACT faces. Valid until the
     *  {@link TransportNetwork#epoch()} changes or the TTL (plus a per-node jitter) elapses. */
    private static final long NET_CACHE_TTL = 40;
    private List<FaceRef> cachedInsertFaces;
    private long cachedNetEpoch = Long.MIN_VALUE;
    private long cachedNetTick = Long.MIN_VALUE;

    /** Loader-backed capability caches (NeoForge BlockCapabilityCache / Fabric BlockApiCache):
     *  one per EXTRACT face for the source inventory it faces, one per known INSERT target for
     *  its destination — so a steady-state burst resolves ZERO capabilities instead of one find()
     *  for the source plus one per target every burst. Dest entries are carried across walk
     *  refreshes while the target face persists. */
    private final EnumMap<Direction, FluidApi.EndpointCache> sourceCaches = new EnumMap<>(Direction.class);
    private Map<FaceRef, FluidApi.EndpointCache> destCaches = new HashMap<>();
    /** Network epoch seen last tick; a change wakes any backed-off EXTRACT face immediately. */
    private long lastBackoffEpoch = Long.MIN_VALUE;

    /** Per-port-face filter state. Created lazily; only EXTRACT/INSERT faces ever get an entry. */
    private final EnumMap<Direction, FaceData> faces = new EnumMap<>(Direction.class);

    public LiquidPipeTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.LIQUID_PIPE_TERMINAL_BE.get(), pos, state);
    }

    private record FaceRef(BlockPos pos, Direction face) {}

    public final class FaceData {
        public final SimpleContainer filter = new SimpleContainer(FILTER_SIZE) {
            @Override
            public void setChanged() {
                super.setChanged();
                LiquidPipeTerminalBlockEntity.this.setChanged();
            }
        };
        public boolean whitelist = true;
        private int bufferMb = 0;
        private final Backoff backoff = new Backoff(BACKOFF_BASE_COOLDOWN, BACKOFF_MAX_COOLDOWN, BACKOFF_RAMP_CAP);
    }

    // ---- Menu accessors ------------------------------------------------------------------
    public FaceData faceData(Direction face) {
        return faces.computeIfAbsent(face, d -> new FaceData());
    }

    public SimpleContainer filterContainer(Direction face) { return faceData(face).filter; }
    public boolean isWhitelist(Direction face) { FaceData d = faces.get(face); return d == null || d.whitelist; }
    public void setWhitelist(Direction face, boolean w) { faceData(face).whitelist = w; setChanged(); }

    public PipeFace mode(Direction face) {
        return getBlockState().getValue(LiquidPipeTerminalBlock.faceProp(face));
    }

    // ---- Tick ----------------------------------------------------------------------------
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long epoch = TransportNetwork.epoch();
        boolean topoChanged = epoch != lastBackoffEpoch;   // network reconfigured → wake backed-off faces
        lastBackoffEpoch = epoch;

        for (Direction face : Direction.values()) {
            if (state.getValue(LiquidPipeTerminalBlock.faceProp(face)) != PipeFace.EXTRACT) continue;
            FaceData data = faceData(face);
            data.bufferMb = Math.min(BUFFER_CAP_MB, data.bufferMb + THROUGHPUT_MB_PER_TICK);
            if (data.bufferMb < BURST_THRESHOLD_MB) continue;            // not enough budget yet
            if (topoChanged) data.backoff.reset();
            if (!data.backoff.ready()) continue;  // backing off

            FluidApi.Endpoint source = sourceCaches.computeIfAbsent(face,
                f -> HydrofarmPlatform.fluids().cache(level, pos.relative(f), f.getOpposite())).get();
            if (source == null || source.fluids().isEmpty()) { data.backoff.recordFutile(); continue; }

            List<FaceRef> targets = insertFaces(level, pos);
            if (targets.isEmpty()) { data.backoff.recordFutile(); continue; }

            // Candidate fluids are the same for every target this burst — resolve once (water-first
            // legacy priority, then whatever else the source holds) instead of per target.
            List<Fluid> candidates = candidateFluids(source);
            int moved = distributeEvenly(level, source, face, targets, data.bufferMb,
                (int) (level.getGameTime() % targets.size()), candidates);
            if (moved == 0) {
                // Source holds fluid (guarded above) yet the sub-level budget moved nothing — treat it
                // as a coarse source (e.g. a Fabric cauldron, which only surrenders whole levels) and
                // try one bucket-sized hop. If that moves, the debit below runs the budget into debt.
                moved = coarsePull(level, source, face, targets,
                    (int) (level.getGameTime() % targets.size()), candidates);
            }
            if (moved > 0) {
                data.bufferMb -= moved;   // a coarse pull can push this negative; the accrual repays it
                data.backoff.recordMoved();
                setChanged();
            } else {
                data.backoff.recordFutile();   // nothing moved (empty/coarse source with full or undersized dests)
            }
        }
    }

    /** Water first (legacy priority), then any other distinct non-empty fluid the source holds. */
    private static List<Fluid> candidateFluids(FluidApi.Endpoint source) {
        List<Fluid> list = new ArrayList<>();
        list.add(Fluids.WATER);
        for (Fluid f : source.fluids()) {
            if (f != Fluids.EMPTY && f != Fluids.WATER) list.add(f);
        }
        return list;
    }

    /** Splits the per-burst mB budget evenly across reachable INSERT faces. Returns total mB moved. */
    private int distributeEvenly(ServerLevel level, FluidApi.Endpoint source, Direction sourceFace,
                                   List<FaceRef> targets, int budget, int rotation, List<Fluid> candidates) {
        int n = targets.size();
        int base = budget / n;
        int extra = budget % n;
        int total = 0;
        int unused = 0;

        for (int k = 0; k < n; k++) {
            int idx = (rotation + k) % n;
            int slice = base + (k < extra ? 1 : 0);
            if (slice == 0) continue;
            int moved = transferTo(level, source, sourceFace, targets.get(idx), slice, candidates);
            total += moved;
            unused += (slice - moved);
        }
        if (unused > 0) {
            for (int k = 0; k < n && unused > 0; k++) {
                int idx = (rotation + k) % n;
                int moved = transferTo(level, source, sourceFace, targets.get(idx), unused, candidates);
                total += moved;
                unused -= moved;
            }
        }
        return total;
    }

    /** Last-resort hop for a coarse source (a Fabric cauldron transacts whole levels — 333 mB per
     *  water level, 1000 mB for lava) that the normal sub-level budget can't satisfy. One
     *  bucket-sized attempt per INSERT target in rotation; first nonzero wins. The caller debits the
     *  moved amount from the throughput budget (into bounded debt), so the long-run rate is unchanged.
     *  Reuses {@link #transferTo} so the fluid filter, dest resolution and all-or-nothing semantics
     *  are identical to a normal hop — only the requested amount differs. */
    private int coarsePull(ServerLevel level, FluidApi.Endpoint source, Direction sourceFace,
                           List<FaceRef> targets, int rotation, List<Fluid> candidates) {
        for (int k = 0; k < targets.size(); k++) {
            int idx = (rotation + k) % targets.size();
            int moved = transferTo(level, source, sourceFace, targets.get(idx),
                FluidContainers.BUCKET_MB, candidates);
            if (moved > 0) return moved;
        }
        return 0;
    }

    private List<FaceRef> insertFaces(ServerLevel level, BlockPos pos) {
        long epoch = TransportNetwork.epoch();
        long now = level.getGameTime();
        if (cachedInsertFaces != null && cachedNetEpoch == epoch
                && now - cachedNetTick < NET_CACHE_TTL + TransportNetwork.cacheTtlJitter(pos)) {
            return cachedInsertFaces;
        }
        cachedInsertFaces = walkInsertFaces(level, pos);
        cachedNetEpoch = epoch;
        cachedNetTick = now;
        // Refresh the per-target capability caches, carrying over entries for faces still present
        // (so the loader-side caches live as long as the target does) and dropping vanished ones.
        Map<FaceRef, FluidApi.EndpointCache> next = new HashMap<>(Math.max(8, cachedInsertFaces.size() * 2));
        for (FaceRef ref : cachedInsertFaces) {
            FluidApi.EndpointCache c = destCaches.get(ref);
            next.put(ref, c != null ? c : HydrofarmPlatform.fluids()
                .cache(level, ref.pos().relative(ref.face()), ref.face().getOpposite()));
        }
        destCaches = next;
        return cachedInsertFaces;
    }

    private List<FaceRef> walkInsertFaces(ServerLevel level, BlockPos start) {
        List<FaceRef> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            if (!level.hasChunkAt(current)) continue;

            BlockState s = level.getBlockState(current);
            if (s.is(HydrofarmRefs.LIQUID_PIPE.get())) {
                for (Direction d : Direction.values()) {
                    BlockPos next = current.relative(d);
                    if (!visited.contains(next)) queue.add(next);
                }
            } else if (s.is(HydrofarmRefs.LIQUID_PIPE_TERMINAL.get())) {
                for (Direction d : Direction.values()) {
                    PipeFace f = s.getValue(LiquidPipeTerminalBlock.faceProp(d));
                    if (f == PipeFace.INSERT) {
                        result.add(new FaceRef(current.immutable(), d));
                    } else if (f == PipeFace.PIPE) {
                        BlockPos next = current.relative(d);
                        if (!visited.contains(next)) queue.add(next);
                    }
                }
            }
        }
        return result;
    }

    private int transferTo(ServerLevel level, FluidApi.Endpoint source, Direction sourceFace,
                            FaceRef target, int maxMb, List<Fluid> candidates) {
        if (maxMb <= 0) return 0;
        BlockState ts = level.getBlockState(target.pos());
        if (!ts.is(HydrofarmRefs.LIQUID_PIPE_TERMINAL.get())
            || ts.getValue(LiquidPipeTerminalBlock.faceProp(target.face())) != PipeFace.INSERT) {
            return 0;
        }
        FluidApi.EndpointCache destCache = destCaches.get(target);
        FluidApi.Endpoint dest = destCache != null ? destCache.get()
            : HydrofarmPlatform.fluids().find(level,                       // defensive fallback —
                target.pos().relative(target.face()), target.face().getOpposite()); // insertFaces() populates the map
        if (dest == null) return 0;

        LiquidPipeTerminalBlockEntity targetBe =
            (level.getBlockEntity(target.pos()) instanceof LiquidPipeTerminalBlockEntity be) ? be : null;

        for (Fluid fluid : candidates) {
            if (fluid == Fluids.EMPTY) continue;
            if (!passesFilter(sourceFace, fluid)) continue;
            if (targetBe != null && !targetBe.passesFilter(target.face(), fluid)) continue;
            // moveTo is all-or-nothing — try each candidate until one moves.
            int moved = source.moveTo(dest, fluid, maxMb);
            if (moved > 0) return moved;
        }
        return 0;
    }

    /** Whether {@code candidate} passes the given face's fluid filter. Unconfigured = pass-all. */
    public boolean passesFilter(Direction face, Fluid candidate) {
        if (candidate == Fluids.EMPTY) return true;
        FaceData d = faces.get(face);
        if (d == null) return true;
        boolean anyFilterSet = false;
        for (int i = 0; i < FILTER_SIZE; i++) {
            ItemStack f = d.filter.getItem(i);
            if (f.isEmpty()) continue;
            Fluid filterFluid = HydrofarmPlatform.fluids().fluidInItem(f);
            if (filterFluid == Fluids.EMPTY) continue;
            anyFilterSet = true;
            if (filterFluid == candidate) return d.whitelist;
        }
        if (!anyFilterSet) return true;
        return !d.whitelist;
    }

    // ---- Persistence ---------------------------------------------------------------------
    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        for (Map.Entry<Direction, FaceData> e : faces.entrySet()) {
            FaceData d = e.getValue();
            ValueOutput c = output.child("face_" + e.getKey().getSerializedName());
            NonNullList<ItemStack> list = NonNullList.withSize(FILTER_SIZE, ItemStack.EMPTY);
            for (int i = 0; i < FILTER_SIZE; i++) list.set(i, d.filter.getItem(i));
            ContainerHelper.saveAllItems(c.child("FilterItems"), list);
            c.putBoolean("Whitelist", d.whitelist);
            c.putInt("BufferMb", d.bufferMb);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        faces.clear();
        for (Direction dir : Direction.values()) {
            input.child("face_" + dir.getSerializedName()).ifPresent(c -> {
                FaceData d = new FaceData();
                NonNullList<ItemStack> list = NonNullList.withSize(FILTER_SIZE, ItemStack.EMPTY);
                c.child("FilterItems").ifPresent(fi -> ContainerHelper.loadAllItems(fi, list));
                for (int i = 0; i < FILTER_SIZE; i++) d.filter.setItem(i, list.get(i));
                d.whitelist = c.getBooleanOr("Whitelist", true);
                d.bufferMb = c.getIntOr("BufferMb", 0);
                faces.put(dir, d);
            });
        }
    }
}
