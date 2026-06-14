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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.HydrofarmPlatform;
import com.kestalkayden.hydrofarm.platform.ItemApi;
import com.kestalkayden.hydrofarm.util.Backoff;

/** Server-tick driver for an item pipe terminal — the pump replacement. A single terminal can carry
 *  an independent {@link PipeFace#EXTRACT}/{@link PipeFace#INSERT} port on each of its six faces
 *  (multi-face), with the mode held in blockstate and the per-face filter/whitelist/buffer held here
 *  in the BE keyed by {@link Direction}.
 *
 *  <p>Each tick, every EXTRACT face accumulates fractional throughput, BFS-walks the connected pipe
 *  graph to gather every INSERT face network-wide (including this terminal's own insert faces), and
 *  distributes items from its facing source across those targets. Both ends filter. This mirrors the
 *  old {@code ItemPumpBlockEntity} exactly, generalised from one-pump-one-face to many-faces-per-node. */
public class ItemPipeTerminalBlockEntity extends BlockEntity {

    public static final int FILTER_SIZE = 5;
    /** Flattened single-tier throughput (milli-items/tick; 1000 = 1 item). 300/tick = 6 items/s —
     *  the deliberate "middle" between the old standard (125) and advanced (500) item pumps. Modest
     *  on purpose: dedicated tech mods handle high-volume logistics. */
    public static final int THROUGHPUT_MILLI_PER_TICK = 300;
    /** Hard cap on unspent milli-budget so a stalled face doesn't accumulate forever. */
    private static final int BUFFER_CAP_MILLI = 10_000;

    /** Adaptive back-off: an EXTRACT face that moves nothing (empty source / no INSERT targets / all
     *  destinations full) ramps its re-probe interval 10→20→40 ticks instead of scanning + allocating
     *  every gated tick, so a jammed or idle extractor stops grinding. Reset on any successful move or
     *  a network topology change. */
    private static final int BACKOFF_BASE_COOLDOWN = 10;
    private static final int BACKOFF_MAX_COOLDOWN  = 40;
    private static final int BACKOFF_RAMP_CAP      = 2;

    /** Cached network-wide INSERT faces, shared across this terminal's EXTRACT faces (they all see
     *  the same network). Valid while {@link TransportNetwork#epoch()} is unchanged and younger than
     *  {@link #NET_CACHE_TTL} plus a per-node jitter ({@link TransportNetwork#cacheTtlJitter}). */
    private static final long NET_CACHE_TTL = 40;
    private List<FaceRef> cachedInsertFaces;
    private long cachedNetEpoch = Long.MIN_VALUE;
    private long cachedNetTick = Long.MIN_VALUE;

    /** Loader-backed capability caches (NeoForge BlockCapabilityCache / Fabric BlockApiCache):
     *  one per EXTRACT face for the source inventory it faces, one per known INSERT target for
     *  its destination — so a steady-state burst resolves ZERO capabilities instead of one find()
     *  for the source plus one per target every burst. Dest entries are carried across walk
     *  refreshes while the target face persists. */
    private final EnumMap<Direction, ItemApi.EndpointCache> sourceCaches = new EnumMap<>(Direction.class);
    private Map<FaceRef, ItemApi.EndpointCache> destCaches = new HashMap<>();
    /** Network epoch seen last tick; a change wakes any backed-off EXTRACT face immediately. */
    private long lastBackoffEpoch = Long.MIN_VALUE;

    /** Per-port-face state. Created lazily; only EXTRACT/INSERT faces ever get an entry. */
    private final EnumMap<Direction, FaceData> faces = new EnumMap<>(Direction.class);

    public ItemPipeTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.ITEM_PIPE_TERMINAL_BE.get(), pos, state);
    }

    /** A specific port face of a specific terminal somewhere in the network. */
    private record FaceRef(BlockPos pos, Direction face) {}

    /** Filter + whitelist + extract buffer for one configured face. */
    public final class FaceData {
        public final SimpleContainer filter = new SimpleContainer(FILTER_SIZE) {
            @Override
            public void setChanged() {
                super.setChanged();
                ItemPipeTerminalBlockEntity.this.setChanged();
            }
        };
        public boolean whitelist = true;
        private int milliBuffer = 0;
        private final Backoff backoff = new Backoff(BACKOFF_BASE_COOLDOWN, BACKOFF_MAX_COOLDOWN, BACKOFF_RAMP_CAP);
    }

    // ---- Menu accessors ------------------------------------------------------------------
    /** The face's state holder, created on demand (used when a GUI opens for that face). */
    public FaceData faceData(Direction face) {
        return faces.computeIfAbsent(face, d -> new FaceData());
    }

    public SimpleContainer filterContainer(Direction face) { return faceData(face).filter; }
    public boolean isWhitelist(Direction face) { FaceData d = faces.get(face); return d == null || d.whitelist; }
    public void setWhitelist(Direction face, boolean w) { faceData(face).whitelist = w; setChanged(); }

    /** Current port mode of a face (from blockstate). */
    public PipeFace mode(Direction face) {
        return getBlockState().getValue(ItemPipeTerminalBlock.faceProp(face));
    }

    // ---- Tick ----------------------------------------------------------------------------
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        long epoch = TransportNetwork.epoch();
        boolean topoChanged = epoch != lastBackoffEpoch;   // network reconfigured → wake backed-off faces
        lastBackoffEpoch = epoch;

        for (Direction face : Direction.values()) {
            if (state.getValue(ItemPipeTerminalBlock.faceProp(face)) != PipeFace.EXTRACT) continue;
            FaceData data = faceData(face);
            data.milliBuffer = Math.min(BUFFER_CAP_MILLI, data.milliBuffer + THROUGHPUT_MILLI_PER_TICK);
            if (data.milliBuffer < 1000) continue;                       // not enough budget yet
            if (topoChanged) data.backoff.reset();
            if (!data.backoff.ready()) continue;  // backing off

            ItemApi.Endpoint source = sourceCaches.computeIfAbsent(face,
                f -> HydrofarmPlatform.items().cache(level, pos.relative(f), f.getOpposite())).get();
            if (source == null) { data.backoff.recordFutile(); continue; }
            List<ItemStack> contents = source.contents();                // hoisted: one snapshot per face-tick
            if (contents.isEmpty()) { data.backoff.recordFutile(); continue; }

            List<FaceRef> targets = insertFaces(level, pos);
            if (targets.isEmpty()) { data.backoff.recordFutile(); continue; }

            int budgetItems = data.milliBuffer / 1000;
            int moved = distributeEvenly(level, source, face, targets, budgetItems,
                (int) (level.getGameTime() % targets.size()), contents);
            if (moved > 0) {
                data.milliBuffer -= moved * 1000;
                data.backoff.recordMoved();
                setChanged();
            } else {
                data.backoff.recordFutile();   // source had items but nothing fit (all dests full) → back off too
            }
        }
    }

    /** Splits the per-tick item budget evenly across reachable INSERT faces. Returns total moved.
     *  {@code contents} is the source's item snapshot, taken once per face-tick and shared (read-only)
     *  across all targets instead of rebuilt per target. */
    private int distributeEvenly(ServerLevel level, ItemApi.Endpoint source, Direction sourceFace,
                                  List<FaceRef> targets, int budgetItems, int rotation, List<ItemStack> contents) {
        int n = targets.size();
        int base = budgetItems / n;
        int extra = budgetItems % n;
        int total = 0;
        int unused = 0;

        for (int k = 0; k < n; k++) {
            int idx = (rotation + k) % n;
            int slice = base + (k < extra ? 1 : 0);
            if (slice == 0) continue;
            int moved = transferTo(level, source, sourceFace, targets.get(idx), slice, contents);
            total += moved;
            unused += (slice - moved);
        }
        if (unused > 0) {
            for (int k = 0; k < n && unused > 0; k++) {
                int idx = (rotation + k) % n;
                int moved = transferTo(level, source, sourceFace, targets.get(idx), unused, contents);
                total += moved;
                unused -= moved;
            }
        }
        return total;
    }

    /** Cached BFS of every INSERT face reachable through the pipe network from this terminal. */
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
        Map<FaceRef, ItemApi.EndpointCache> next = new HashMap<>(Math.max(8, cachedInsertFaces.size() * 2));
        for (FaceRef ref : cachedInsertFaces) {
            ItemApi.EndpointCache c = destCaches.get(ref);
            next.put(ref, c != null ? c : HydrofarmPlatform.items()
                .cache(level, ref.pos().relative(ref.face()), ref.face().getOpposite()));
        }
        destCaches = next;
        return cachedInsertFaces;
    }

    /** Walks item pipes + terminals from this terminal, collecting every INSERT face. Terminals are
     *  traversed only through their PIPE faces; their EXTRACT/INSERT faces point at inventories, not
     *  the network. The starting terminal is included, so an extract face can feed an insert face on
     *  the same block. */
    private List<FaceRef> walkInsertFaces(ServerLevel level, BlockPos start) {
        List<FaceRef> result = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            if (!level.hasChunkAt(current)) continue;   // never force-load an edge chunk from a tick

            BlockState s = level.getBlockState(current);
            if (s.is(HydrofarmRefs.ITEM_PIPE.get())) {
                for (Direction d : Direction.values()) {
                    BlockPos next = current.relative(d);
                    if (!visited.contains(next)) queue.add(next);
                }
            } else if (s.is(HydrofarmRefs.ITEM_PIPE_TERMINAL.get())) {
                for (Direction d : Direction.values()) {
                    PipeFace f = s.getValue(ItemPipeTerminalBlock.faceProp(d));
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

    /** Moves up to {@code maxItems} of any filter-passing item from the source → one INSERT face's
     *  target inventory. Both the source face's filter and the target face's filter apply. */
    private int transferTo(ServerLevel level, ItemApi.Endpoint source, Direction sourceFace,
                            FaceRef target, int maxItems, List<ItemStack> contents) {
        if (maxItems <= 0) return 0;
        BlockState ts = level.getBlockState(target.pos());
        if (!ts.is(HydrofarmRefs.ITEM_PIPE_TERMINAL.get())
            || ts.getValue(ItemPipeTerminalBlock.faceProp(target.face())) != PipeFace.INSERT) {
            return 0;
        }
        ItemApi.EndpointCache destCache = destCaches.get(target);
        ItemApi.Endpoint dest = destCache != null ? destCache.get()
            : HydrofarmPlatform.items().find(level,                        // defensive fallback —
                target.pos().relative(target.face()), target.face().getOpposite()); // insertFaces() populates the map
        if (dest == null) return 0;

        ItemPipeTerminalBlockEntity targetBe =
            (level.getBlockEntity(target.pos()) instanceof ItemPipeTerminalBlockEntity be) ? be : null;

        int moved = 0;
        for (ItemStack proto : contents) {
            if (moved >= maxItems) break;
            if (!passesFilter(sourceFace, proto)) continue;
            if (targetBe != null && !targetBe.passesFilter(target.face(), proto)) continue;
            moved += source.moveTo(dest, proto, maxItems - moved);
        }
        return moved;
    }

    /** Whether {@code stack} passes the given face's filter. An unconfigured face (no FaceData, or an
     *  all-empty filter) passes everything; otherwise whitelist/blacklist semantics apply. */
    public boolean passesFilter(Direction face, ItemStack stack) {
        FaceData d = faces.get(face);
        if (d == null) return true;
        boolean anyFilterSet = false;
        for (int i = 0; i < FILTER_SIZE; i++) {
            ItemStack f = d.filter.getItem(i);
            if (f.isEmpty()) continue;
            anyFilterSet = true;
            if (ItemStack.isSameItem(f, stack)) return d.whitelist;
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
            c.putInt("MilliBuffer", d.milliBuffer);
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
                d.milliBuffer = c.getIntOr("MilliBuffer", 0);
                faces.put(dir, d);
            });
        }
    }
}
