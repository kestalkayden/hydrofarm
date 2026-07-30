package com.kestalkayden.hydrofarm.block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.platform.EnergyBuffer;
import com.kestalkayden.hydrofarm.util.PositionStagger;

/** Energy bank that clusters with adjacent cells into a shared pool. Per-cell capacity is
 *  {@link #CELL_CAPACITY}; the cluster total is computed by summing members. Energy is TYPELESS
 *  (no fluid-identity guards). Generators push in and consumers pull out via the insert+extract
 *  capability; the cell also runs its own banking pull (see {@link #serverTick}) to draw idle
 *  network sources — the Hydroelectric Generator and foreign push-sources like TechReborn solar
 *  panels — that a purely passive bank would never receive. */
public class EnergyCellBlockEntity extends BlockEntity implements EnergyBuffer {

    public static final int CELL_CAPACITY = 100_000;

    /** Cached once — Direction.values() clones its 6-element array on every call, and the BFS
     *  below calls it per polled member. Mirrors LiquidTankBlockEntity#ALL_DIRS. */
    private static final Direction[] ALL_DIRS = Direction.values();

    /** Banking pull: every {@link #PULL_INTERVAL} ticks the cell draws up to {@link #PULL_RATE} from
     *  the connected network's non-cell sources. Matches the consumers' 256/5-tick (~51 E/t) rate. */
    private static final int PULL_INTERVAL = 5;
    private static final int PULL_RATE = 256;

    private int storedEnergy = 0;

    private final EnergyNetwork energyNet = new EnergyNetwork();

    /** Lazily-created, cached loader-specific energy capability wrapper. Caching is required:
     *  cluster operations must enroll the SAME per-cell instance in a transaction so snapshot
     *  rollback restores every modified cell. Type is loader-specific, held as Object. */
    private Object energyExposure;

    // -----------------------------------------------------------------------------------------
    // Cluster cache — mirrors LiquidTankBlockEntity exactly (same epoch + 40-tick TTL scheme).
    // -----------------------------------------------------------------------------------------

    private static final AtomicLong CLUSTER_EPOCH = new AtomicLong();
    private static final long CLUSTER_CACHE_TTL = 40L;
    /** Per-BE jitter [0,16) added to the TTL so cache refreshes don't re-synchronize after an
     *  epoch bump (which resets every cell's cache to the same tick — a fixed TTL would then
     *  re-BFS every cell on the SAME tick every 40t). Position-hashed, deterministic. */
    private final long clusterTtlJitter = PositionStagger.offset(worldPosition, 16);
    private Cluster cachedCluster;
    private long cachedClusterEpoch = Long.MIN_VALUE;
    private long cachedClusterTick  = Long.MIN_VALUE;

    /** Per-tick cache of the resolved member BEs — see {@link #clusterMembers()}. */
    private List<EnergyCellBlockEntity> cachedMembers;
    private long cachedMembersTick = Long.MIN_VALUE;
    private Cluster cachedMembersCluster;

    public EnergyCellBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.ENERGY_CELL_BE.get(), pos, state);
    }

    // -----------------------------------------------------------------------------------------
    // EnergyBuffer implementation (per-block, used by loader wrappers and EnergyNetwork pull)
    // -----------------------------------------------------------------------------------------

    @Override
    public int insertEnergy(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, CELL_CAPACITY - storedEnergy);
        if (accepted > 0) {
            storedEnergy += accepted;
            setChanged();   // client broadcast is throttled in serverTick, not emitted per-mutation
        }
        return accepted;
    }

    /** Extract up to {@code amount} energy from this cell. Returns the amount actually extracted. */
    public int extractEnergy(int amount) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, storedEnergy);
        if (taken > 0) {
            storedEnergy -= taken;
            setChanged();
        }
        return taken;
    }

    @Override public int getEnergyStored()   { return storedEnergy; }
    @Override public int getEnergyCapacity() { return CELL_CAPACITY; }
    @Override public void setEnergyStored(int v) {
        storedEnergy = Math.max(0, Math.min(CELL_CAPACITY, v));
        setChanged();
    }

    // -----------------------------------------------------------------------------------------
    // Faint charge glow — flip LIT when the bank holds energy (mirrors the tank/repulser light path)
    // -----------------------------------------------------------------------------------------

    /** Server tick: banks idle network sources into the cell, then flips {@link EnergyCellBlock#LIT}
     *  as stored energy crosses 0 so a charged cell emits a faint light. Only fires {@code setBlock}
     *  on the transition; the explicit {@code checkBlock} relight is required (setBlock alone is
     *  unreliable on Fabric). */
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        // Bank idle network sources (the generator + foreign push-sources). skipCellSources = true so
        // two cells linked only by a pipe never pull from each other (energy sloshing).
        if (level.getGameTime() % PULL_INTERVAL == 0) {
            energyNet.pull(level, pos, this, PULL_RATE, true);
        }

        boolean shouldBeLit = storedEnergy > 0;
        if (state.hasProperty(EnergyCellBlock.LIT) && state.getValue(EnergyCellBlock.LIT) != shouldBeLit) {
            level.setBlock(pos, state.setValue(EnergyCellBlock.LIT, shouldBeLit),
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            level.getLightEngine().checkBlock(pos);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Loader capability wrapper cache
    // -----------------------------------------------------------------------------------------

    /** Returns the cached loader-specific energy wrapper, creating it via {@code factory} on first
     *  use. Every caller gets the same instance so transaction enrollment is consistent. */
    @SuppressWarnings("unchecked")
    public <T> T energyExposure(Function<EnergyCellBlockEntity, T> factory) {
        if (energyExposure == null) energyExposure = factory.apply(this);
        return (T) energyExposure;
    }

    // -----------------------------------------------------------------------------------------
    // Transaction snapshot (used by Fabric SnapshotParticipant rollback)
    // -----------------------------------------------------------------------------------------

    public record Snapshot(int storedEnergy) {}

    public Snapshot snapshot() { return new Snapshot(storedEnergy); }

    /** Restores from a transaction snapshot. A simulated (aborted) transfer enrolls every cluster
     *  member and rolls them all back, so the common case here is restoring a value that never
     *  changed; the guard keeps that from calling setChanged() — which reaches
     *  {@code Level#updateNeighbourForOutputSignal} and is far from free — N times per probe. */
    public void restore(Snapshot snapshot) {
        int clamped = Math.max(0, Math.min(CELL_CAPACITY, snapshot.storedEnergy()));
        if (this.storedEnergy == clamped) return;
        this.storedEnergy = clamped;
        setChanged();
    }

    // -----------------------------------------------------------------------------------------
    // Cluster break redistribution
    // -----------------------------------------------------------------------------------------

    /** Called by vanilla just before this BE is removed. Redistributes this cell's stored energy
     *  into the remaining cluster so no energy is lost on break. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // Cluster-cache invalidation on removal is handled by the neighbours' updateShape (their
        // connection flag to this cell flips when it becomes air), so no epoch bump is needed here.
        if (level == null || level.isClientSide() || storedEnergy <= 0) return;
        int freed = storedEnergy;
        // Clear our own state before redistribute so we are not double-counted.
        this.storedEnergy = 0;
        for (Direction d : ALL_DIRS) {
            BlockPos neighborPos = pos.relative(d);
            if (level.getBlockState(neighborPos).is(HydrofarmRefs.ENERGY_CELL.get())) {
                // EXCLUDE this cell from the refill: it is still ENERGY_CELL in the level, so the BFS
                // re-includes it — without the exclude, up to a full cell of `freed` would be put back
                // into the doomed cell and destroyed on removal.
                redistributeCluster(level, neighborPos, freed, pos);
                return;
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Cluster record
    // -----------------------------------------------------------------------------------------

    /** Connected set of energy cells. Members are sorted (y, z, x) bottom-up. layerSizes maps
     *  each Y level to the number of cells at that level — carried for symmetry with the tank's
     *  Cluster record; not used by energy redistribution but preserved for completeness. */
    public record Cluster(BlockPos min, BlockPos max, List<BlockPos> members,
                          Map<Integer, Integer> layerSizes, Set<BlockPos> memberSet) {
        public int size() { return members.size(); }
        public boolean isMultiBlock() { return size() > 1; }
        public boolean contains(BlockPos pos) { return memberSet.contains(pos); }
    }

    // -----------------------------------------------------------------------------------------
    // Cluster cache access
    // -----------------------------------------------------------------------------------------

    public Cluster findCluster() {
        if (level == null) return null;
        long epoch = CLUSTER_EPOCH.get();
        long now   = level.getGameTime();
        Cluster cached = cachedCluster;
        if (cached != null && cachedClusterEpoch == epoch
                && now - cachedClusterTick < CLUSTER_CACHE_TTL + clusterTtlJitter) {
            return cached;
        }
        Cluster fresh = findClusterAt(level, worldPosition);
        cachedCluster      = fresh;
        cachedClusterEpoch = epoch;
        cachedClusterTick  = now;
        seedMembers(fresh, epoch, now);
        return fresh;
    }

    /** Publishes a freshly-walked cluster to every other member so ONE walk serves the whole cluster.
     *  See {@link LiquidTankBlockEntity#seedMembers} for the full rationale — this is the same O(N^2)
     *  to O(N) collapse, and the same rule applies: only seed BEs resolved through {@code this.level},
     *  and only from the instance {@link #findCluster()}, never from the static {@link #findClusterAt}
     *  (which {@link #redistributeCluster} runs with exclude-on-remove semantics). The cell has no
     *  renderer, so every caller here is on the server tick thread. */
    private void seedMembers(Cluster fresh, long epoch, long now) {
        if (fresh == null || level == null || !fresh.isMultiBlock()) return;
        for (BlockPos pos : fresh.members()) {
            if (pos.equals(worldPosition)) continue;
            if (level.getBlockEntity(pos) instanceof EnergyCellBlockEntity m) {
                m.cachedCluster      = fresh;
                m.cachedClusterEpoch = epoch;
                m.cachedClusterTick  = now;
            }
        }
    }

    /** Resolved cluster member BEs, cached per tick. The loader capability wrappers query the member
     *  list on every insert/extract/getAmount/getCapacity probe — many times per tick under active
     *  transfer — and each rebuild is N {@code getBlockEntity} lookups; resolve once per tick instead,
     *  keyed by the cached {@link Cluster} identity (a topology change yields a fresh Cluster, which
     *  rebuilds) and the tick. Returns {@code [this]} for a single cell. */
    public List<EnergyCellBlockEntity> clusterMembers() {
        if (level == null) return List.of(this);
        Cluster cluster = findCluster();
        if (cluster == null || !cluster.isMultiBlock()) return List.of(this);
        long now = level.getGameTime();
        if (cachedMembers != null && cachedMembersTick == now && cachedMembersCluster == cluster) {
            return cachedMembers;
        }
        List<EnergyCellBlockEntity> list = new ArrayList<>(cluster.size());
        for (BlockPos pos : cluster.members()) {
            if (level.getBlockEntity(pos) instanceof EnergyCellBlockEntity m) list.add(m);
        }
        List<EnergyCellBlockEntity> result = list.isEmpty() ? List.of(this) : list;
        cachedMembers = result;
        cachedMembersTick = now;
        cachedMembersCluster = cluster;
        return result;
    }

    public static void bumpClusterEpoch() {
        CLUSTER_EPOCH.incrementAndGet();
    }

    // -----------------------------------------------------------------------------------------
    // BFS cluster finder
    // -----------------------------------------------------------------------------------------

    public static Cluster findClusterAt(BlockGetter level, BlockPos start) {
        if (!isEnergyCell(level.getBlockState(start))) return null;

        // Same guard the pipe walks use: never force-load an edge chunk from a tick/render path.
        net.minecraft.world.level.Level lvl =
            level instanceof net.minecraft.world.level.Level l ? l : null;

        Set<BlockPos>         members    = new HashSet<>();
        Map<Integer, Integer> layerSizes = new HashMap<>();
        Deque<BlockPos>       queue      = new ArrayDeque<>();
        BlockPos seed = start.immutable();
        queue.add(seed);
        members.add(seed);
        layerSizes.merge(seed.getY(), 1, Integer::sum);

        int minX = seed.getX(), minY = seed.getY(), minZ = seed.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction d : ALL_DIRS) {
                BlockPos next = current.relative(d);
                if (members.contains(next)) continue;
                if (lvl != null && !lvl.hasChunkAt(next)) continue;
                if (!isEnergyCell(level.getBlockState(next))) continue;
                BlockPos immutable = next.immutable();
                members.add(immutable);
                queue.add(immutable);
                layerSizes.merge(immutable.getY(), 1, Integer::sum);
                if (next.getX() < minX) minX = next.getX();
                if (next.getY() < minY) minY = next.getY();
                if (next.getZ() < minZ) minZ = next.getZ();
                if (next.getX() > maxX) maxX = next.getX();
                if (next.getY() > maxY) maxY = next.getY();
                if (next.getZ() > maxZ) maxZ = next.getZ();
            }
        }

        List<BlockPos> sorted = members.stream()
            .sorted((a, b) -> {
                int c = Integer.compare(a.getY(), b.getY());
                if (c != 0) return c;
                c = Integer.compare(a.getZ(), b.getZ());
                if (c != 0) return c;
                return Integer.compare(a.getX(), b.getX());
            })
            .toList();
        return new Cluster(
            new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
            sorted, Map.copyOf(layerSizes), Set.copyOf(members));
    }

    private static boolean isEnergyCell(BlockState state) {
        return state.is(HydrofarmRefs.ENERGY_CELL.get());
    }

    // -----------------------------------------------------------------------------------------
    // Redistribute energy across cluster
    // -----------------------------------------------------------------------------------------

    /** Redistributes the cluster's total stored energy across members, used when a cell is placed
     *  (onPlace) so the new member shares the pool. Fill is bottom-up (members sorted y,z,x), not
     *  even — lower cells fill to capacity first, matching the tank's settle order. Returns any
     *  energy that didn't fit (should be 0 under normal circumstances). */
    public static int redistributeCluster(net.minecraft.world.level.Level level, BlockPos seed) {
        return redistributeCluster(level, seed, 0, null);
    }

    /** Same as {@link #redistributeCluster(net.minecraft.world.level.Level, BlockPos)} but also
     *  absorbs an extra {@code extraEnergy} bolus (e.g. energy freed when a cell is broken). */
    public static int redistributeCluster(net.minecraft.world.level.Level level, BlockPos seed,
                                           int extraEnergy) {
        return redistributeCluster(level, seed, extraEnergy, null);
    }

    /** Pools the cluster's stored energy (plus an optional {@code extraEnergy} bolus) and spreads it
     *  across members, SKIPPING {@code excludePos} entirely. The exclude is used on break: the leaving
     *  cell is still {@code ENERGY_CELL} in the level, so the BFS re-includes it — without the skip its
     *  share of the refill would be destroyed when it's removed. Returns any energy that didn't fit
     *  (cluster at capacity). */
    public static int redistributeCluster(net.minecraft.world.level.Level level, BlockPos seed,
                                           int extraEnergy, BlockPos excludePos) {
        Cluster cluster = findClusterAt(level, seed);
        if (cluster == null) return extraEnergy;

        long total = extraEnergy;
        for (BlockPos pos : cluster.members()) {
            if (excludePos != null && pos.equals(excludePos)) continue;
            if (level.getBlockEntity(pos) instanceof EnergyCellBlockEntity m) {
                total += m.storedEnergy;
            }
        }
        if (total == 0) return 0;

        // Settle bottom-up in ONE pass (members are sorted y,z,x): each member's target is
        // min(remaining, capacity), and members past the fill frontier settle to 0. Do NOT break when
        // remaining hits 0 — the loop must keep going to clear the members above the frontier.
        //
        // This replaces a clear-then-refill shape that wrote every member twice. Its clear pass
        // assigned the backing field directly (`m.storedEnergy = 0`) instead of going through
        // setEnergyStored, so it never reached setChanged() -> LevelChunk#markUnsaved. A cell zeroed
        // in a chunk that saw no other mutation was therefore never marked dirty and reloaded holding
        // its PRE-clear energy — i.e. the redistribute MINTED energy across a save/load. (Increases
        // always dirtied via insertEnergy, so only the zeroing could be lost.) It was masked by the
        // cosmetic LIT flip in serverTick dirtying the chunk a tick later; that guard is incidental,
        // not a design, and gating LIT for any reason would have made the dupe live.
        long remaining = total;
        for (BlockPos pos : cluster.members()) {
            if (excludePos != null && pos.equals(excludePos)) continue;
            if (!(level.getBlockEntity(pos) instanceof EnergyCellBlockEntity m)) continue;
            int target = (int) Math.min(remaining, CELL_CAPACITY);
            remaining -= target;
            if (m.storedEnergy == target) continue;   // no change, no write, no dirty-mark
            m.setEnergyStored(target);                // clamps and calls setChanged()
        }
        return (int) remaining;
    }

    // -----------------------------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("Energy", storedEnergy);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        storedEnergy = Math.max(0, Math.min(CELL_CAPACITY, in.getIntOr("Energy", 0)));
    }

    // -----------------------------------------------------------------------------------------
    // Network sync
    // -----------------------------------------------------------------------------------------

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveCustomOnly(provider);
    }
}
