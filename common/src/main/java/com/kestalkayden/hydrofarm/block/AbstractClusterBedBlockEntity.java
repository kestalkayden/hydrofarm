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
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.kestalkayden.hydrofarm.util.PositionStagger;

/** Abstract parent for any "cluster bed" — a block that forms a single-layer adjacency cluster
 *  with same-typed neighbors and pools water + Liquid XP + an item buffer across all members.
 *  Concrete subclasses (HydroponicsBed, HusbandryBed, ButcherBed, etc.) add their own per-bed
 *  state (planter quadrants, animal stalls, etc.) and their own server-tick loop.
 *
 *  <p>Cluster discovery scopes per bed-type: cluster BFS only walks through neighbors of the
 *  same {@link Block} instance. A hydroponics bed touching a husbandry bed is two separate
 *  clusters, not one — same as the existing hydroponics/tree separation. */
public abstract class AbstractClusterBedBlockEntity extends BlockEntity implements Container {

    public static final int WATER_CAPACITY_MB = 1_000;
    public static final int XP_BUFFER_CAPACITY_MB = 200;
    public static final int SLOT_COUNT = 9;

    /** 60-second rolling window split into 12 buckets of 5s (= 100 ticks) each. Lazy rotation:
     *  only advances on harvest or sample, never on tick. Not persisted — rolling data is
     *  ephemeral, world reload starts the window over. */
    public static final int HARVEST_BUCKET_COUNT = 12;
    public static final int HARVEST_BUCKET_TICKS = 100;
    public static final int HARVEST_WINDOW_TICKS = HARVEST_BUCKET_COUNT * HARVEST_BUCKET_TICKS;

    private static final Direction[] HORIZONTAL_DIRS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    protected int waterMb = 0;
    protected int liquidXpMb = 0;
    protected final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    private final Map<Item, int[]> harvestBuckets = new HashMap<>();
    private int currentBucketIdx = 0;
    private long lastBucketRotationTick = Long.MIN_VALUE;

    /** Global structure nonce — bumped whenever any cluster bed connects to/disconnects from a
     *  neighbor. Stamped into {@link #cachedCluster} so a structural edit anywhere invalidates every
     *  bed's cache at once. Cross-level over-invalidation is harmless and structural edits are rare. */
    private static final AtomicLong CLUSTER_EPOCH = new AtomicLong();
    /** Backstop max-age for {@link #cachedCluster} (ticks). Covers membership changes that don't flip
     *  a connection — chiefly a neighboring chunk loading in next to an already-loaded bed, which
     *  fires no updateShape. 40t = 2s, invisible for the renderer and immaterial for pooling. */
    private static final long CLUSTER_CACHE_TTL = 40L;
    private Cluster cachedCluster;
    private long cachedClusterEpoch = Long.MIN_VALUE;
    private long cachedClusterTick = Long.MIN_VALUE;

    /** Per-tick cache of the resolved member BEs. {@link #clusterMembers()} is called several times
     *  per tick by some beds (Husbandry probes species counts + per-stall food + water each tick), and
     *  each call otherwise rebuilds the list with N {@code getBlockEntity} lookups. Resolve once per
     *  tick instead; keyed by the cached {@link Cluster} identity so a topology change (which yields a
     *  fresh Cluster from {@link #findCluster()}) rebuilds it, and the per-tick key keeps the held BE
     *  references at most one tick old. */
    private List<AbstractClusterBedBlockEntity> cachedMembers;
    private long cachedMembersTick = Long.MIN_VALUE;
    private Cluster cachedMembersCluster;

    /** Per-BE jitter [0,16) added to {@link #CLUSTER_CACHE_TTL} so cache refreshes don't
     *  re-synchronize. An epoch bump (any structural edit) resets every bed's cache to the same
     *  tick; with a fixed TTL the whole farm then re-BFSes on the SAME tick every 40t — a
     *  synchronized O(total beds × cluster size) spike. Position-hashed (deterministic across
     *  save/load), and the differing per-bed periods keep the phases drifting apart for good. */
    private final long clusterTtlJitter = PositionStagger.offset(worldPosition, 16);

    /** Global item-contents revision + cluster-shared count memo for {@link #countItemInCluster}.
     *  External inserts consult {@link #canAccept} once per exposed slot — hoppers/pipes iterate all
     *  members×slots on BOTH loaders — and each consult walked the whole cluster's buffers, making a
     *  failed feed insert O((members×slots)²). The memo lives on the cluster's FIRST member so every
     *  member's query shares one cache (a per-bed memo would still be quadratic across the slot walk).
     *  Keyed (tick, rev): one walk per item type per actual content change. The tick half of the key
     *  also bounds any mutation that somehow bypasses {@link #bumpItemRev} to a single tick of
     *  staleness. Both loaders' item capabilities route writes through {@link #setItem}, which bumps
     *  the revision, so no such bypass exists today — keep it that way. */
    private static final AtomicLong ITEM_CONTENTS_REV = new AtomicLong();
    private final Map<Item, Integer> clusterCountMemo = new HashMap<>();
    private long countMemoTick = Long.MIN_VALUE;
    private long countMemoRev = Long.MIN_VALUE;

    /** Bump after any item-buffer mutation so {@link #countItemInCluster} memos recompute. */
    protected static void bumpItemRev() { ITEM_CONTENTS_REV.incrementAndGet(); }

    /** Lazily-created, cached platform fluid wrapper (Fabric Storage / NeoForge Handler). Cached
     *  so cluster ops enroll the SAME per-bed instance in a transaction; the loader supplies the
     *  concrete type via a factory. Mirrors LiquidTankBlockEntity's fluidExposure. */
    private Object fluidExposure;

    protected AbstractClusterBedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Returns the cached loader-specific fluid wrapper, creating it via {@code factory} on first
     *  use, so the capability registration and sibling-bed enrollment share one instance per BE. */
    @SuppressWarnings("unchecked")
    public <T> T fluidExposure(Function<AbstractClusterBedBlockEntity, T> factory) {
        if (fluidExposure == null) fluidExposure = factory.apply(this);
        return (T) fluidExposure;
    }

    // ---- Subclass hooks -------------------------------------------------------------------

    /** Drive any subclass-specific tick logic (growth, food consumption, cull cycles, etc.).
     *  Called from the block's BlockEntityTicker — already gated to server side. */
    public abstract void serverTick(ServerLevel level, BlockPos pos, BlockState state);

    /** The fluid this bed produces in its output slot. Hydroponics/Tree/Butcher emit
     *  liquid_xp (from harvest / cull XP); Husbandry will emit fluid_milk in v0.6.2.
     *  The cluster fluid storage queries this so a pump connected to any cluster reads
     *  the right fluid type without hardcoding it. */
    public abstract Fluid getOutputFluid();

    /** Whether this bed's output fluid is enabled at all. The XP beds override this with their
     *  config toggle (gameplay.*BedXp); when false the bed produces nothing
     *  ({@link #depositLiquidXpToCluster} no-ops), its rate gauges read 0, the GUI hides the
     *  gauge (via synced menu data), and the cluster fluid capability stops exposing the output
     *  slot (hiding it from Jade and pipes). Pre-existing fluid stays stored — invisible but
     *  intact, and still bottle-extractable by hand — so toggling is non-destructive both ways.
     *  Husbandry (milk) never overrides this. */
    public boolean outputFluidEnabled() { return true; }

    /** Save subclass-specific state. Called after the parent's pooled state is written. */
    protected void saveSubclassData(ValueOutput out) {}

    /** Load subclass-specific state. Called after the parent's pooled state is read. */
    protected void loadSubclassData(ValueInput in) {}

    /** Drop subclass-specific contents (e.g. installed planters, captured animals) at the bed
     *  position when the block is removed. Called BEFORE the parent's pooled-state cleanup. */
    protected void dropSubclassContents(ServerLevel level, BlockPos pos) {}

    // ---- Water ----------------------------------------------------------------------------

    public int getWaterMb() { return waterMb; }
    public int getCapacityMb() { return WATER_CAPACITY_MB; }
    public int getRoomMb() { return WATER_CAPACITY_MB - waterMb; }

    public int insertWater(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, getRoomMb());
        if (accepted > 0) {
            waterMb += accepted;
            setChanged();
        }
        return accepted;
    }

    public int extractWater(int amount) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, waterMb);
        if (taken > 0) {
            waterMb -= taken;
            setChanged();
        }
        return taken;
    }

    public void setWaterMb(int amount) {
        this.waterMb = Math.max(0, Math.min(WATER_CAPACITY_MB, amount));
        setChanged();
    }

    public int extractWaterFromCluster(int amount) {
        if (amount <= 0) return 0;
        int taken = 0;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            if (taken >= amount) break;
            taken += m.extractWater(amount - taken);
        }
        return taken;
    }

    public int clusterWaterMb() {
        int total = 0;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) total += m.waterMb;
        return total;
    }

    public int clusterWaterCapMb() {
        return clusterMembers().size() * WATER_CAPACITY_MB;
    }

    // ---- Liquid XP ------------------------------------------------------------------------

    public int getLiquidXpMb() { return liquidXpMb; }
    public int getXpCapacityMb() { return XP_BUFFER_CAPACITY_MB; }
    public int getXpRoomMb() { return XP_BUFFER_CAPACITY_MB - liquidXpMb; }

    public int insertLiquidXp(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, getXpRoomMb());
        if (accepted > 0) {
            liquidXpMb += accepted;
            setChanged();
        }
        return accepted;
    }

    public int extractLiquidXp(int amount) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, liquidXpMb);
        if (taken > 0) {
            liquidXpMb -= taken;
            setChanged();
        }
        return taken;
    }

    public void setLiquidXpMb(int amount) {
        this.liquidXpMb = Math.max(0, Math.min(XP_BUFFER_CAPACITY_MB, amount));
        setChanged();
    }

    public int depositLiquidXpToCluster(int amount) {
        if (amount <= 0 || !outputFluidEnabled()) return 0;   // config-disabled bed produces nothing
        int remaining = amount;
        int totalAccepted = 0;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            if (remaining <= 0) break;
            int accepted = m.insertLiquidXp(remaining);
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted;
    }

    /** Removal-path XP deposit: preserves XP that ALREADY exists (freed by a broken bed). Unlike
     *  {@link #depositLiquidXpToCluster} it bypasses the {@link #outputFluidEnabled()} config gate —
     *  the gate must stop NEW production, not destroy stored fluid (the toggle is documented as
     *  non-destructive) — and skips {@code excludePos}, the doomed bed, which is still a cluster
     *  member while {@code preRemoveSideEffects} runs; without the skip its just-emptied buffer is
     *  the first deposit target and the XP is destroyed with it. */
    public int salvageLiquidXpToCluster(int amount, BlockPos excludePos) {
        if (amount <= 0) return 0;
        int remaining = amount;
        int totalAccepted = 0;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            if (remaining <= 0) break;
            if (excludePos != null && m.worldPosition.equals(excludePos)) continue;
            int accepted = m.insertLiquidXp(remaining);
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted;
    }

    public int extractLiquidXpFromCluster(int amount) {
        if (amount <= 0) return 0;
        int taken = 0;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            if (taken >= amount) break;
            taken += m.extractLiquidXp(amount - taken);
        }
        return taken;
    }

    public int clusterXpMb() {
        int total = 0;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) total += m.liquidXpMb;
        return total;
    }

    public int clusterXpCapMb() {
        return clusterMembers().size() * XP_BUFFER_CAPACITY_MB;
    }

    // ---- Container (9-slot item buffer) ---------------------------------------------------

    public NonNullList<ItemStack> getItems() { return items; }

    @Override public int getContainerSize() { return SLOT_COUNT; }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = ContainerHelper.removeItem(items, slot, amount);
        if (!stack.isEmpty()) {
            bumpItemRev();
            setChanged();
        }
        return stack;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = ContainerHelper.takeItem(items, slot);
        if (!stack.isEmpty()) bumpItemRev();
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        bumpItemRev();
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        bumpItemRev();
        setChanged();
    }

    // ---- Cluster-pooled item ops ----------------------------------------------------------

    public ItemStack depositItemIntoCluster(ItemStack stack) {
        return depositItemIntoCluster(stack, null);
    }

    /** As {@link #depositItemIntoCluster(ItemStack)} but skips the member at {@code excludePos}
     *  (nullable). The break path routes freed items through a neighbor while the broken bed is
     *  still a cluster member — without the skip they'd land right back in the doomed bed's
     *  just-cleared slots and be destroyed with it. */
    public ItemStack depositItemIntoCluster(ItemStack stack, BlockPos excludePos) {
        if (stack.isEmpty()) return stack;
        ItemStack remaining = stack.copy();
        List<AbstractClusterBedBlockEntity> members = clusterMembers();
        int n = members.size();
        if (n == 0) return remaining;

        // Start at THIS bed's index in the cluster so each bed's harvests fill its own slots
        // first before overflowing to neighbors. Without this, all harvests pile into
        // cluster[0] and only the hopper under that bed sees items.
        int selfIdx = 0;
        for (int i = 0; i < n; i++) {
            if (members.get(i).getBlockPos().equals(worldPosition)) {
                selfIdx = i;
                break;
            }
        }

        for (int i = 0; i < n; i++) {
            if (remaining.isEmpty()) break;
            AbstractClusterBedBlockEntity target = members.get((selfIdx + i) % n);
            if (excludePos != null && target.worldPosition.equals(excludePos)) continue;
            remaining = target.depositItemLocal(remaining);
        }
        return remaining;
    }

    private ItemStack depositItemLocal(ItemStack stack) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack existing = items.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(space, stack.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                    bumpItemRev();
                    setChanged();
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, stack.copy());
                stack.setCount(0);
                bumpItemRev();
                setChanged();
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    public ItemStack extractItemFromCluster(Item filter, int maxAmount) {
        if (filter == null || maxAmount <= 0) return ItemStack.EMPTY;
        ItemStack result = ItemStack.EMPTY;
        int remaining = maxAmount;
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            if (remaining <= 0) break;
            for (int i = 0; i < m.items.size(); i++) {
                if (remaining <= 0) break;
                ItemStack slot = m.items.get(i);
                if (slot.isEmpty() || slot.getItem() != filter) continue;
                int taken = Math.min(remaining, slot.getCount());
                if (result.isEmpty()) {
                    result = slot.copyWithCount(taken);
                } else {
                    result.grow(taken);
                }
                slot.shrink(taken);
                bumpItemRev();
                m.setChanged();
                remaining -= taken;
            }
        }
        return result;
    }

    /** Counts how much of {@code filter} is sitting across the entire cluster's item buffers.
     *  Memoized cluster-wide (on the first member) keyed by (tick, item rev) — see the field
     *  comment on {@code ITEM_CONTENTS_REV}. Sole consumer today is the animal beds' feed cap in
     *  {@link #canAccept}, which external inserts probe once per exposed slot. */
    public int countItemInCluster(Item filter) {
        if (filter == null) return 0;
        List<AbstractClusterBedBlockEntity> members = clusterMembers();
        AbstractClusterBedBlockEntity memo = members.get(0);   // never empty: List.of(this) fallback
        long now = level != null ? level.getGameTime() : Long.MIN_VALUE;
        long rev = ITEM_CONTENTS_REV.get();
        if (memo.countMemoTick != now || memo.countMemoRev != rev) {
            memo.clusterCountMemo.clear();
            memo.countMemoTick = now;
            memo.countMemoRev = rev;
        }
        Integer cached = memo.clusterCountMemo.get(filter);
        if (cached != null) return cached;
        int total = 0;
        for (AbstractClusterBedBlockEntity m : members) {
            for (int i = 0; i < m.items.size(); i++) {
                ItemStack slot = m.items.get(i);
                if (!slot.isEmpty() && slot.getItem() == filter) total += slot.getCount();
            }
        }
        memo.clusterCountMemo.put(filter, total);
        return total;
    }

    /** True if any slot anywhere in the cluster can take another item — empty, or a stack below ITS
     *  OWN max stack size (per-stack: a full 16-stack of eggs is NOT room). Mirrors the fill rule in
     *  {@link #depositItemLocal} so the production-pause gate matches reality. Used by the bed
     *  subclasses to skip a harvest that would otherwise overflow to the ground as item entities.
     *  Cheap: {@link #clusterMembers()} is per-tick cached, so this is just primitive slot reads. */
    public boolean clusterHasItemRoom() {
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            for (int i = 0; i < m.items.size(); i++) {
                ItemStack s = m.items.get(i);
                if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) return true;
            }
        }
        return false;
    }

    /** Whether {@code item} may be pump/hopper-EXTRACTED from this bed's shared buffer. Default
     *  allows anything; animal beds override to lock their feed in (food is consumed input, not a
     *  pumpable output). Consulted by the per-loader item wrappers on extraction only — insertion
     *  and the animals' own eating are unaffected. */
    public boolean canPumpOut(Item item) {
        return true;
    }

    /** Whether the cluster will accept more of {@code item} via external insertion. Default: always.
     *  Animal beds cap each feed type so an input pump can't hoard thousands of items in the buffer. */
    public boolean canAccept(Item item) {
        return true;
    }

    // ---- Harvest tracking -----------------------------------------------------------------

    private void rotateBucketsIfNeeded(long currentTick) {
        if (lastBucketRotationTick == Long.MIN_VALUE) {
            lastBucketRotationTick = currentTick;
            return;
        }
        long elapsed = currentTick - lastBucketRotationTick;
        if (elapsed < HARVEST_BUCKET_TICKS) return;
        int slotsToAdvance = (int) Math.min(HARVEST_BUCKET_COUNT, elapsed / HARVEST_BUCKET_TICKS);
        for (int i = 0; i < slotsToAdvance; i++) {
            currentBucketIdx = (currentBucketIdx + 1) % HARVEST_BUCKET_COUNT;
            for (int[] buckets : harvestBuckets.values()) {
                buckets[currentBucketIdx] = 0;
            }
        }
        lastBucketRotationTick += (long) slotsToAdvance * HARVEST_BUCKET_TICKS;
    }

    public void recordHarvest(ServerLevel level, Item item, int count) {
        if (item == null || count <= 0) return;
        rotateBucketsIfNeeded(level.getGameTime());
        harvestBuckets.computeIfAbsent(item, k -> new int[HARVEST_BUCKET_COUNT])[currentBucketIdx] += count;
    }

    public Map<Item, Integer> sampleHarvestWindow() {
        if (level instanceof ServerLevel sl) {
            rotateBucketsIfNeeded(sl.getGameTime());
        }
        Map<Item, Integer> out = new HashMap<>();
        for (var e : harvestBuckets.entrySet()) {
            int sum = 0;
            for (int v : e.getValue()) sum += v;
            if (sum > 0) out.put(e.getKey(), sum);
        }
        return out;
    }

    public Map<Item, Integer> clusterHarvestRate() {
        Map<Item, Integer> total = new HashMap<>();
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            for (var e : m.sampleHarvestWindow().entrySet()) {
                total.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return total;
    }

    // ---- Production scaling (per-species diminishing returns) ------------------------------

    /** Animals/species at which production efficiency halves. Each additional animal of a species
     *  is worth less than the last, so a single-species monoculture soft-caps and a varied pen
     *  out-produces a mega-herd of one type. Applied per species by the animal beds. */
    public static final double PRODUCTION_HALF_LIFE = 16.0;
    private static final double PRODUCTION_DECAY = Math.pow(0.5, 1.0 / PRODUCTION_HALF_LIFE);

    /** Effective producing count for {@code count} animals of one species — the geometric sum of
     *  {@code 0.5^((k-1)/H)} for k=1..count. Near-linear for small herds (2→~2.0, 8→~6.9), then
     *  flattens toward the {@code 1/(1-decay)} asymptote (~23.6 at H=16), so e.g. 64 of one species
     *  produce like ~22. Returns a fractional count; callers scale rate/interval by it. */
    public static double effectiveSpeciesCount(int count) {
        if (count <= 0) return 0.0;
        if (count == 1) return 1.0;
        return (1.0 - Math.pow(PRODUCTION_DECAY, count)) / (1.0 - PRODUCTION_DECAY);
    }

    // ---- Cluster discovery ----------------------------------------------------------------

    public record Cluster(BlockPos min, BlockPos max, List<BlockPos> members, Set<BlockPos> memberSet,
                          long version) {
        public int size() { return members.size(); }
        public boolean contains(BlockPos pos) { return memberSet.contains(pos); }
    }

    /** Cluster this bed belongs to, cached. The renderer calls this every frame for every bed (and
     *  the server tick calls it repeatedly), so the underlying BFS — which allocates sets/queues and
     *  a sorted copy each run — must not happen per frame: across a pen it was an O(N^2) cost.
     *  Recomputed only when the structure {@link #CLUSTER_EPOCH} changes or the cache ages past
     *  {@link #CLUSTER_CACHE_TTL}. */
    public Cluster findCluster() {
        if (level == null) return null;
        long epoch = CLUSTER_EPOCH.get();
        long now = level.getGameTime();
        Cluster cached = cachedCluster;
        if (cached != null && cachedClusterEpoch == epoch
                && now - cachedClusterTick < CLUSTER_CACHE_TTL + clusterTtlJitter) {
            return cached;
        }
        Cluster fresh = findClusterAt(level, worldPosition, getBlockState().getBlock());
        cachedCluster = fresh;
        cachedClusterEpoch = epoch;
        cachedClusterTick = now;
        return fresh;
    }

    /** Invalidate every bed's cached cluster. Called when a cluster bed connects to or disconnects
     *  from a same-type neighbor (see {@link AbstractClusterBedBlock#updateShape}). */
    public static void bumpClusterEpoch() {
        CLUSTER_EPOCH.incrementAndGet();
    }

    public static Cluster findClusterAt(BlockGetter level, BlockPos start, Block bedBlock) {
        if (!level.getBlockState(start).is(bedBlock)) return null;

        // Same guard the pipe walks use: never force-load an edge chunk from a tick/render path.
        // A border-straddling cluster degrades to its loaded half until the neighbor chunk loads
        // (the TTL'd cache re-merges it within ~2s) instead of sync-loading chunks every refresh.
        Level lvl = level instanceof Level l ? l : null;

        Set<BlockPos> members = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos seed = start.immutable();
        queue.add(seed);
        members.add(seed);

        int minX = seed.getX(), minZ = seed.getZ();
        int maxX = minX, maxZ = minZ;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction d : HORIZONTAL_DIRS) {
                BlockPos next = current.relative(d);
                if (members.contains(next)) continue;
                if (lvl != null && !lvl.hasChunkAt(next)) continue;
                if (!level.getBlockState(next).is(bedBlock)) continue;
                BlockPos immutable = next.immutable();
                members.add(immutable);
                queue.add(immutable);
                if (next.getX() < minX) minX = next.getX();
                if (next.getZ() < minZ) minZ = next.getZ();
                if (next.getX() > maxX) maxX = next.getX();
                if (next.getZ() > maxZ) maxZ = next.getZ();
            }
        }

        int y = seed.getY();
        List<BlockPos> sorted = members.stream()
            .sorted((a, b) -> {
                int c = Integer.compare(a.getZ(), b.getZ());
                if (c != 0) return c;
                return Integer.compare(a.getX(), b.getX());
            })
            .toList();
        // Stable identity hash over the (deterministically sorted) members — changes iff membership
        // changes. Lets render code key per-pen caches off the cached cluster without re-hashing the
        // member list every frame.
        long version = 1L;
        for (BlockPos p : sorted) version = version * 1099511628211L + p.asLong();
        return new Cluster(
            new BlockPos(minX, y, minZ), new BlockPos(maxX, y, maxZ),
            sorted, Set.copyOf(members), version);
    }

    public List<AbstractClusterBedBlockEntity> clusterMembers() {
        if (level == null) return List.of(this);
        Cluster cluster = findCluster();
        if (cluster == null) return List.of(this);
        long now = level.getGameTime();
        if (cachedMembers != null && cachedMembersTick == now && cachedMembersCluster == cluster) {
            return cachedMembers;
        }
        List<AbstractClusterBedBlockEntity> list = new ArrayList<>(cluster.size());
        for (BlockPos pos : cluster.members()) {
            if (level.getBlockEntity(pos) instanceof AbstractClusterBedBlockEntity m) list.add(m);
        }
        List<AbstractClusterBedBlockEntity> result = list.isEmpty() ? List.of(this) : list;
        cachedMembers = result;
        cachedMembersTick = now;
        cachedMembersCluster = cluster;
        return result;
    }

    /** Type-narrowed cluster view. Cluster discovery is already block-typed so every member is
     *  the same subclass as {@code this}, but the API returns the parent type — subclasses use
     *  this helper when they need to access fields specific to their own subclass. */
    protected <T extends AbstractClusterBedBlockEntity> List<T> clusterMembersOfType(Class<T> clazz) {
        List<T> result = new ArrayList<>();
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            if (clazz.isInstance(m)) result.add(clazz.cast(m));
        }
        return result;
    }

    /** A stable per-bed phase offset in {@code [0, period)} derived from block position. Periodic
     *  per-tick gates (crop growth, cull drips, save flushes) add this to {@code gameTime} before the
     *  modulo so beds don't all fire on the SAME global tick — spreading a large farm's burst evenly
     *  across the window instead of spiking once per period. Deterministic across save/load (computed
     *  from {@link #worldPosition}, never stored); the golden-ratio mix decorrelates adjacent beds. */
    protected int phaseOffset(int period) {
        return PositionStagger.offset(worldPosition, period);
    }

    public static int redistributeCluster(Level level, BlockPos seed, Block bedBlock) {
        return redistributeCluster(level, seed, 0, bedBlock, null);
    }

    /** Pools the cluster's water (plus an optional freed bolus) and settles it across members,
     *  SKIPPING {@code excludePos} (nullable). The exclude is used on break: the leaving bed is
     *  still {@code bedBlock} in the level, so the BFS re-includes it — without the skip part of
     *  the refill lands in the doomed bed and is destroyed on removal. Returns what didn't fit. */
    public static int redistributeCluster(Level level, BlockPos seed, int extraMb, Block bedBlock,
                                          BlockPos excludePos) {
        Cluster cluster = findClusterAt(level, seed, bedBlock);
        if (cluster == null) return extraMb;

        int totalWater = extraMb;
        for (BlockPos pos : cluster.members()) {
            if (excludePos != null && pos.equals(excludePos)) continue;
            if (level.getBlockEntity(pos) instanceof AbstractClusterBedBlockEntity m) {
                totalWater += m.getWaterMb();
                m.setWaterMb(0);
            }
        }
        if (totalWater == 0) return 0;

        int remaining = totalWater;
        for (BlockPos pos : cluster.members()) {
            if (remaining <= 0) break;
            if (excludePos != null && pos.equals(excludePos)) continue;
            if (level.getBlockEntity(pos) instanceof AbstractClusterBedBlockEntity m) {
                int accepted = m.insertWater(remaining);
                remaining -= accepted;
            }
        }
        return remaining;
    }

    // ---- Lifecycle ------------------------------------------------------------------------

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level == null || level.isClientSide()) return;

        // Subclass-specific drops first (planters, animals). They go on the ground at THIS
        // bed's position rather than into neighbor cluster pools — pumps would suction them.
        if (level instanceof ServerLevel sl) {
            dropSubclassContents(sl, pos);
        }

        int freedWater = waterMb;
        this.waterMb = 0;
        int freedXp = liquidXpMb;
        this.liquidXpMb = 0;

        // Stored items CAN go to a neighbor cluster — they were already pump-extractable, so
        // preserving them across the break is fine.
        List<ItemStack> freedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                freedItems.add(items.get(i).copy());
                items.set(i, ItemStack.EMPTY);
            }
        }
        if (!freedItems.isEmpty()) bumpItemRev();

        if (freedWater <= 0 && freedXp <= 0 && freedItems.isEmpty()) return;

        // Route freed contents through a neighbor, EXCLUDING this bed everywhere: it is still a
        // cluster member at this point (the block is removed only after this hook returns), so a
        // non-excluding deposit would put the freed water/XP/items straight back into the doomed
        // bed's just-cleared buffers and destroy them on removal.
        for (Direction d : HORIZONTAL_DIRS) {
            BlockPos neighborPos = pos.relative(d);
            if (level.getBlockState(neighborPos).is(getBlockState().getBlock())) {
                if (freedWater > 0) redistributeCluster(level, neighborPos, freedWater, getBlockState().getBlock(), pos);
                if (level.getBlockEntity(neighborPos) instanceof AbstractClusterBedBlockEntity n) {
                    if (freedXp > 0) n.salvageLiquidXpToCluster(freedXp, pos);
                    for (ItemStack drop : freedItems) {
                        ItemStack overflow = n.depositItemIntoCluster(drop, pos);
                        if (!overflow.isEmpty()) {
                            Containers.dropItemStack(level,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, overflow);
                        }
                    }
                }
                return;
            }
        }
        // No same-block neighbor: this was the last bed of its cluster, so there is nowhere to hand
        // the pooled contents off to. Freed items drop on the ground; the pooled water and Liquid XP
        // are lost here — they have no item form to drop, matching the documented fluid-loss-on-
        // isolated-break of LiquidTankBlockEntity#preRemoveSideEffects.
        for (ItemStack drop : freedItems) {
            Containers.dropItemStack(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("WaterMb", waterMb);
        out.putInt("LiquidXpMb", liquidXpMb);
        ContainerHelper.saveAllItems(out, items);
        saveSubclassData(out);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        this.waterMb = Math.max(0, Math.min(WATER_CAPACITY_MB, in.getIntOr("WaterMb", 0)));
        this.liquidXpMb = Math.max(0, Math.min(XP_BUFFER_CAPACITY_MB, in.getIntOr("LiquidXpMb", 0)));
        ContainerHelper.loadAllItems(in, items);
        loadSubclassData(in);
    }

    /** Push a block-entity data packet to watching clients so a render-relevant change (an
     *  installed/evicted animal) shows up immediately. {@code setChanged()} alone only marks the BE
     *  for saving — without this the change wouldn't reach the client until a chunk reload or some
     *  other update happened to re-sync the bed. */
    protected void syncToClients() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveCustomOnly(provider);
    }
}
