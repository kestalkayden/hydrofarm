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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.util.PositionStagger;

/** Tank that holds up to {@link #TANK_CAPACITY_MB} of a single fluid. Phase 1 refactor: storage
 *  now carries fluid identity (was water-only) so later phases can extend to lava, XP, etc.
 *  Behavior is still water-only externally — only water is inserted/extracted today — but the
 *  data model is fluid-agnostic. */
public class LiquidTankBlockEntity extends BlockEntity {

    public static final int TANK_CAPACITY_MB = 16_000;

    /** Current fluid in the tank, or {@link Fluids#EMPTY} when the tank holds nothing. The tank
     *  refuses an insert of a different fluid while non-empty; emptying it clears the type. */
    private Fluid fluid = Fluids.EMPTY;
    private int amountMb = 0;

    /** Lazily-created, cached platform fluid-capability wrapper (Fabric Storage / NeoForge
     *  Handler). Caching matters: cluster operations enroll the SAME per-tank instance in a
     *  transaction so snapshot rollback restores every modified tank. The wrapper type is
     *  loader-specific, so it's held as Object and built via a loader-supplied factory. */
    private Object fluidExposure;

    /** Tracks last emitted light so sync() only invokes the light engine when it changes. */
    private int lastLightLevel = 0;

    /** Cluster cache — same epoch+TTL scheme commit 475c6a9 gave the beds. The renderer calls
     *  {@link #findCluster()} every frame for every visible tank, and the BFS allocates sets/queues
     *  + a sorted copy each run (O(N^2) across a multi-tank cluster in view); cache it so the walk
     *  runs only when a tank connects/disconnects ({@link #bumpClusterEpoch()} from
     *  {@link LiquidTankBlock#updateShape}) or the cache ages past {@link #CLUSTER_CACHE_TTL}. */
    private static final AtomicLong CLUSTER_EPOCH = new AtomicLong();
    private static final long CLUSTER_CACHE_TTL = 40L;
    /** Per-BE jitter [0,16) added to the TTL so cache refreshes don't re-synchronize after an
     *  epoch bump (which resets every tank's cache to the same tick — a fixed TTL would then
     *  re-BFS every tank on the SAME tick every 40t). Position-hashed, deterministic. */
    private final long clusterTtlJitter = PositionStagger.offset(worldPosition, 16);
    private Cluster cachedCluster;
    private long cachedClusterEpoch = Long.MIN_VALUE;
    private long cachedClusterTick = Long.MIN_VALUE;

    /** Per-tick cache of the resolved member BEs — see {@link #clusterMembers()}. */
    private List<LiquidTankBlockEntity> cachedMembers;
    private long cachedMembersTick = Long.MIN_VALUE;
    private Cluster cachedMembersCluster;

    /** Per-tick cache of the cluster's total mB + fluid for the renderer. Tank amounts only change
     *  on a server tick (synced to the client), so recomputing once per tick instead of per frame
     *  collapses the renderer's residual O(N) member re-walk. */
    private long cachedTotalsTick = Long.MIN_VALUE;
    private long cachedTotalsEpoch = Long.MIN_VALUE;
    private long cachedTotalMb = 0;
    private Fluid cachedClusterFluid = Fluids.EMPTY;

    public LiquidTankBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.LIQUID_TANK_BE.get(), pos, state);
    }

    /** Returns the cached loader-specific fluid wrapper, creating it via {@code factory} on first
     *  use. The capability registration and sibling-tank enrollment both go through here so every
     *  caller shares one instance per BE (required for cluster-wide transaction rollback). */
    @SuppressWarnings("unchecked")
    public <T> T fluidExposure(Function<LiquidTankBlockEntity, T> factory) {
        if (fluidExposure == null) fluidExposure = factory.apply(this);
        return (T) fluidExposure;
    }

    public Fluid getFluid() { return fluid; }
    public int getAmountMb() { return amountMb; }
    public int getCapacityMb() { return TANK_CAPACITY_MB; }
    public int getRoomMb() { return TANK_CAPACITY_MB - amountMb; }

    /** The single fluid this tank's whole cluster holds — the first non-empty member (bottom-up), or
     *  {@link Fluids#EMPTY} if the cluster is empty. A cluster claims one fluid; callers (the loader
     *  insert guards and the bucket-fill path) reject a different fluid to keep clusters uniform and
     *  never mix water with Liquid XP. Falls back to this tank's own fluid when not clustered. */
    public Fluid clusterFluid() {
        if (level == null) return fluid;
        Cluster cluster = findCluster();
        if (cluster == null) return fluid;
        for (BlockPos pos : cluster.members()) {
            if (level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m && m.fluid != Fluids.EMPTY) {
                return m.fluid;
            }
        }
        return Fluids.EMPTY;
    }

    /** Returns mB accepted (0 if the requested fluid is incompatible with current contents or
     *  the tank is full). Sets the fluid type if the tank was empty and we accepted any amount. */
    public int insert(Fluid resource, int requestedMb) {
        if (requestedMb <= 0 || resource == Fluids.EMPTY) return 0;
        if (fluid != Fluids.EMPTY && fluid != resource) return 0;
        int accepted = Math.min(requestedMb, getRoomMb());
        if (accepted > 0) {
            if (fluid == Fluids.EMPTY) fluid = resource;
            amountMb += accepted;
            sync();
        }
        return accepted;
    }

    /** Returns mB extracted (0 if the requested fluid doesn't match current contents). Clears
     *  the fluid type when fully drained so a different fluid can be inserted next. */
    public int extract(Fluid resource, int requestedMb) {
        if (requestedMb <= 0 || resource == Fluids.EMPTY) return 0;
        if (fluid != resource) return 0;
        int taken = Math.min(requestedMb, amountMb);
        if (taken > 0) {
            amountMb -= taken;
            if (amountMb == 0) fluid = Fluids.EMPTY;
            sync();
        }
        return taken;
    }

    // ---- cluster-aware fill/drain for the manual bucket path -------------------------------------
    // Players emptying/filling a bucket by hand go through LiquidTankBlock#useItemOn, which does NOT
    // pass through the loader Storage/Handler — those cluster-aware paths only run for pipes/hoppers.
    // Without these, a bucket only ever fills/drains the single clicked tank, so a multi-tank cluster
    // caps at one tank's capacity. These mirror LiquidTankFluidStorage's settle order (fill bottom-up,
    // drain top-down) so per-tank state stays consistent with the visual. No transaction enrollment:
    // a player interaction isn't part of a Fabric transfer transaction, so direct mutation is correct.

    /** Cluster-aware insert: fills members bottom-up ({@link #clusterMembers()} is sorted y,z,x).
     *  Returns mB accepted. Equivalent to {@link #insert} when not in a multi-tank cluster. */
    public int insertIntoCluster(Fluid resource, int requestedMb) {
        int remaining = requestedMb;
        int accepted = 0;
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (remaining <= 0) break;
            int a = m.insert(resource, remaining);
            accepted += a;
            remaining -= a;
        }
        return accepted;
    }

    /** Cluster-aware extract: drains members top-down so the bottom stays full longest. Returns mB
     *  taken. Equivalent to {@link #extract} when not in a multi-tank cluster. */
    public int extractFromCluster(Fluid resource, int requestedMb) {
        List<LiquidTankBlockEntity> members = clusterMembers();
        int remaining = requestedMb;
        int taken = 0;
        for (int i = members.size() - 1; i >= 0; i--) {
            if (remaining <= 0) break;
            int t = members.get(i).extract(resource, remaining);
            taken += t;
            remaining -= t;
        }
        return taken;
    }

    /** Total free space across the cluster for {@code fluid} — counts members that are empty or
     *  already hold {@code fluid} (a mixed-in different-fluid member can't accept it). */
    public int clusterRoomMb(Fluid fluid) {
        int room = 0;
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (m.getFluid() == Fluids.EMPTY || m.getFluid() == fluid) room += m.getRoomMb();
        }
        return room;
    }

    /** Total mB of the cluster's fluid, summed across the members holding it. 0 when empty. */
    public int clusterAmountMb() {
        Fluid f = clusterFluid();
        if (f == Fluids.EMPTY) return 0;
        int total = 0;
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (m.getFluid() == f) total += m.getAmountMb();
        }
        return total;
    }

    /** Atomic snapshot record for transaction rollback. */
    public record Snapshot(Fluid fluid, int amountMb) {}

    public Snapshot snapshot() { return new Snapshot(fluid, amountMb); }

    /** Restores contents from a transaction snapshot — also used by the BE itself when external
     *  code needs to set state directly (e.g., creative tooling, future fill commands). */
    public void restore(Snapshot snapshot) {
        this.fluid = snapshot.fluid;
        this.amountMb = Math.max(0, Math.min(TANK_CAPACITY_MB, snapshot.amountMb));
        sync();
    }

    /** Called by vanilla just before this BE is removed (block broken / replaced). Transfers any
     *  remaining water into an adjacent tank so the cluster's pool is preserved. Water is only
     *  lost if no neighbor tank exists OR the remaining cluster's capacity is already full. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null || level.isClientSide() || amountMb <= 0) return;
        Fluid freedFluid = fluid;
        int freedMb = amountMb;
        // Clear our own state so the upcoming redistribute doesn't double-count us via
        // findClusterAt(...) — at this moment our block is still in the level.
        this.fluid = Fluids.EMPTY;
        this.amountMb = 0;
        for (Direction d : Direction.values()) {
            BlockPos neighborPos = pos.relative(d);
            if (level.getBlockState(neighborPos).is(HydrofarmRefs.LIQUID_TANK.get())) {
                // EXCLUDE this tank from the refill: it is still LIQUID_TANK in the level, so the
                // BFS re-includes it — without the exclude the bottom-up settle would pour the freed
                // fluid right back into the doomed (just-cleared) tank and destroy it on removal.
                redistributeCluster(level, neighborPos, freedFluid, freedMb, pos);
                return;
            }
        }
    }

    /** Persist + push a chunk-update so the client renderer sees the new fill level. Flips
     *  the LIT blockstate to drive Properties.lightLevel, then explicitly calls the light
     *  engine — setBlock alone didn't reliably trigger relighting on Fabric. */
    private void sync() {
        setChanged();
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        boolean shouldBeLit = getLightLevel() > 0;
        if (state.hasProperty(LiquidTankBlock.LIT) && state.getValue(LiquidTankBlock.LIT) != shouldBeLit) {
            state = state.setValue(LiquidTankBlock.LIT, shouldBeLit);
            // UPDATE_CLIENTS: LIT drives the glow only (light engine is invoked explicitly below);
            // no neighbor reacts to it, so skip the neighborChanged fan-out.
            level.setBlock(getBlockPos(), state, Block.UPDATE_CLIENTS);
        }
        level.sendBlockUpdated(getBlockPos(), state, state, Block.UPDATE_CLIENTS);
        int newLight = getLightLevel();
        if (newLight != lastLightLevel) {
            lastLightLevel = newLight;
            level.getLightEngine().checkBlock(getBlockPos());
        }
    }

    /** Light emission for the current fluid contents. The actual emission is a fixed 12 (from
     *  Properties.lightLevel + LIT) on both loaders; this per-fluid value only drives the LIT
     *  boolean (> 0 ? lit : unlit). */
    public int getLightLevel() {
        return amountMb > 0 ? lightLevelFor(fluid) : 0;
    }

    private static int lightLevelFor(Fluid f) {
        if (f == Fluids.LAVA) return 15;
        if (f == HydrofarmRefs.LIQUID_XP.get()) return 10;
        return 0;
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("AmountMb", amountMb);
        out.putString("Fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        // Backward compat: pre-v0.2-multiblock saves only had "WaterMb" (implied water).
        if (in.getString("Fluid").isPresent()) {
            this.amountMb = Math.max(0, Math.min(TANK_CAPACITY_MB, in.getIntOr("AmountMb", 0)));
            this.fluid = parseFluidId(in.getString("Fluid").get());
            if (amountMb == 0) this.fluid = Fluids.EMPTY;
        } else {
            int legacy = Math.max(0, Math.min(TANK_CAPACITY_MB, in.getIntOr("WaterMb", 0)));
            this.amountMb = legacy;
            this.fluid = legacy > 0 ? Fluids.WATER : Fluids.EMPTY;
        }
    }

    /** Connected set of water tanks (any shape — not restricted to rectangular prisms).
     *  {@code members} are sorted (y, z, x) so layer iteration is bottom-up. {@code layerSizes}
     *  maps each Y level to the number of tanks at that level, used by the layer-fill math so
     *  water settles correctly for L/T/hollow clusters where layer sizes differ. */
    public record Cluster(BlockPos min, BlockPos max, List<BlockPos> members,
                          Map<Integer, Integer> layerSizes, Set<BlockPos> memberSet) {
        public int size()   { return members.size(); }
        public boolean isMultiBlock() { return size() > 1; }
        public int layerSize(int y) { return layerSizes.getOrDefault(y, 0); }
        /** Capacity of all layers strictly below {@code y}, given a per-tank capacity. */
        public long capacityBelow(int y, int tankCap) {
            long cum = 0;
            for (int y2 = min.getY(); y2 < y; y2++) {
                cum += (long) layerSize(y2) * tankCap;
            }
            return cum;
        }
        public boolean contains(BlockPos pos) { return memberSet.contains(pos); }
    }

    /** Returns the connected-tank cluster this BE belongs to, or null when {@code level} is
     *  unavailable. Any connected shape is a cluster — L, T, hollow are all accepted; layer fill
     *  math handles varying layer sizes. */
    public Cluster findCluster() {
        if (level == null) return null;
        long epoch = CLUSTER_EPOCH.get();
        long now = level.getGameTime();
        Cluster cached = cachedCluster;
        if (cached != null && cachedClusterEpoch == epoch
                && now - cachedClusterTick < CLUSTER_CACHE_TTL + clusterTtlJitter) {
            return cached;
        }
        Cluster fresh = findClusterAt(level, worldPosition);
        cachedCluster = fresh;
        cachedClusterEpoch = epoch;
        cachedClusterTick = now;
        return fresh;
    }

    /** Resolved cluster member BEs, cached per tick. The loader capability wrappers query the member
     *  list on every insert/extract/getAmount/getCapacity probe — many times per tick under active
     *  transfer — and each rebuild is N {@code getBlockEntity} lookups; resolve once per tick instead,
     *  keyed by the cached {@link Cluster} identity (a topology change yields a fresh Cluster, which
     *  rebuilds) and the tick. Returns {@code [this]} when not in a multi-tank cluster. */
    public List<LiquidTankBlockEntity> clusterMembers() {
        if (level == null) return List.of(this);
        Cluster cluster = findCluster();
        if (cluster == null || !cluster.isMultiBlock()) return List.of(this);
        long now = level.getGameTime();
        if (cachedMembers != null && cachedMembersTick == now && cachedMembersCluster == cluster) {
            return cachedMembers;
        }
        List<LiquidTankBlockEntity> list = new ArrayList<>(cluster.size());
        for (BlockPos pos : cluster.members()) {
            if (level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m) list.add(m);
        }
        List<LiquidTankBlockEntity> result = list.isEmpty() ? List.of(this) : list;
        cachedMembers = result;
        cachedMembersTick = now;
        cachedMembersCluster = cluster;
        return result;
    }

    /** Invalidate every tank's cached cluster. Called when a tank connects to / disconnects from a
     *  neighbour (see {@link LiquidTankBlock#updateShape}). */
    public static void bumpClusterEpoch() {
        CLUSTER_EPOCH.incrementAndGet();
    }

    /** Refresh the per-tick cluster total + fluid cache for {@code cluster}, which must be this
     *  tank's own {@link #findCluster()} result (the sole caller). Keying on (tick, epoch) is valid
     *  only under that contract: membership can change only via {@link LiquidTankBlock#updateShape},
     *  which bumps the epoch. Cheap no-op when already computed this tick. Read the results via
     *  {@link #clusterTotalMb()} / {@link #clusterFluidCached()} — no allocation on the hot path. */
    public void ensureClusterTotals(Cluster cluster) {
        if (level == null || cluster == null) return;
        long now = level.getGameTime();
        long epoch = CLUSTER_EPOCH.get();
        if (cachedTotalsTick == now && cachedTotalsEpoch == epoch) return;
        // Reported fluid = first non-empty member's; sum ONLY members holding THAT fluid, so a
        // physically-mixed cluster never renders another fluid's mB as this fluid (water-as-XP bug).
        Fluid f = Fluids.EMPTY;
        for (BlockPos pos : cluster.members()) {
            if (level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m && m.getFluid() != Fluids.EMPTY) {
                f = m.getFluid();
                break;
            }
        }
        long total = 0;
        if (f != Fluids.EMPTY) {
            for (BlockPos pos : cluster.members()) {
                if (level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m && m.getFluid() == f) {
                    total += m.getAmountMb();
                }
            }
        }
        cachedTotalMb = total;
        cachedClusterFluid = f;
        cachedTotalsTick = now;
        cachedTotalsEpoch = epoch;
    }

    public long clusterTotalMb()  { return cachedTotalMb; }
    public Fluid clusterFluidCached() { return cachedClusterFluid; }

    /** BFSes connected water tanks from {@code start}, recording layer sizes. Returns null only
     *  when the start position itself is not a tank. */
    public static Cluster findClusterAt(BlockGetter level, BlockPos start) {
        if (!isLiquidTank(level.getBlockState(start))) return null;

        // Same guard the pipe walks use: never force-load an edge chunk from a tick/render path.
        net.minecraft.world.level.Level lvl =
            level instanceof net.minecraft.world.level.Level l ? l : null;

        Set<BlockPos> members = new HashSet<>();
        Map<Integer, Integer> layerSizes = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos seed = start.immutable();
        queue.add(seed);
        members.add(seed);
        layerSizes.merge(seed.getY(), 1, Integer::sum);

        int minX = seed.getX(), minY = seed.getY(), minZ = seed.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = current.relative(d);
                if (members.contains(next)) continue;
                if (lvl != null && !lvl.hasChunkAt(next)) continue;
                if (!isLiquidTank(level.getBlockState(next))) continue;
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

    /** Redistributes the cluster's total water bottom-up across its members. Caps any excess
     *  (returns the amount lost). Caller is responsible for any side effects (sync etc) — uses
     *  {@link #restore} + {@link #insert} which sync internally. */
    public static int redistributeCluster(net.minecraft.world.level.Level level, BlockPos seed) {
        return redistributeCluster(level, seed, Fluids.EMPTY, 0, null);
    }

    /** Same as {@link #redistributeCluster(net.minecraft.world.level.Level, BlockPos)} but also
     *  adds an extra-fluid bolus (e.g., the fluid freed when a tank is broken) and skips
     *  {@code excludePos} (nullable) entirely. The exclude is used on break: the leaving tank is
     *  still LIQUID_TANK in the level, so the BFS re-includes it — without the skip the bottom-up
     *  settle pours the freed fluid back into the doomed tank, destroyed on removal. Returns the
     *  amount of fluid that didn't fit and was lost. */
    public static int redistributeCluster(net.minecraft.world.level.Level level, BlockPos seed,
                                           Fluid extraFluid, int extraMb, BlockPos excludePos) {
        Cluster cluster = findClusterAt(level, seed);
        if (cluster == null) return extraMb;  // seed isn't a tank, can't redistribute

        // Balance exactly ONE fluid — never sum or relabel across different fluids. A tank cluster can
        // be physically mixed (e.g. an empty member that grabbed a stray fluid before the claim-guard
        // existed); summing+relabelling those was the duplication bug. Pick the freed fluid if given,
        // else the cluster's first non-empty fluid.
        Fluid fluid = extraFluid;
        if (fluid == Fluids.EMPTY) {
            for (BlockPos pos : cluster.members()) {
                if (excludePos != null && pos.equals(excludePos)) continue;
                if (level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m && m.getAmountMb() > 0) {
                    fluid = m.getFluid();
                    break;
                }
            }
        }
        if (fluid == Fluids.EMPTY) return extraMb;   // nothing of this fluid to balance

        // Sum only members holding THIS fluid (plus the freed bolus).
        long total = extraMb;
        for (BlockPos pos : cluster.members()) {
            if (excludePos != null && pos.equals(excludePos)) continue;
            if (level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m && m.getFluid() == fluid) {
                total += m.getAmountMb();
            }
        }
        if (total == 0) return 0;

        // Settle bottom-up in ONE pass (members are sorted y,z,x): each this-fluid-or-empty member's
        // target is min(remaining, capacity); members holding a DIFFERENT fluid are left untouched.
        // Only members whose contents actually change get written — the old clear-then-refill shape
        // synced every member twice (2N block updates + 2N light-engine checks per redistribute).
        long remaining = total;
        for (BlockPos pos : cluster.members()) {
            if (excludePos != null && pos.equals(excludePos)) continue;
            if (!(level.getBlockEntity(pos) instanceof LiquidTankBlockEntity m)) continue;
            if (m.getFluid() != fluid && m.getFluid() != Fluids.EMPTY) continue;
            int target = (int) Math.min(remaining, TANK_CAPACITY_MB);
            remaining -= target;
            Fluid targetFluid = target > 0 ? fluid : Fluids.EMPTY;
            if (m.getFluid() == targetFluid && m.getAmountMb() == target) continue;  // no change, no sync
            m.restore(new Snapshot(targetFluid, target));
        }
        return (int) remaining;
    }

    private static boolean isLiquidTank(BlockState state) {
        return state.is(HydrofarmRefs.LIQUID_TANK.get());
    }

    private static Fluid parseFluidId(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) return Fluids.EMPTY;
        try {
            Identifier id = Identifier.fromNamespaceAndPath(s.substring(0, colon), s.substring(colon + 1));
            return BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.getValue(id) : Fluids.EMPTY;
        } catch (Exception ignored) {
            return Fluids.EMPTY;
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Carries the BE NBT in chunk-load + setBlockUpdated packets so the client renderer sees
     *  the current fill level. Default returns an empty tag, so without this the renderer reads 0. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveCustomOnly(provider);
    }
}
