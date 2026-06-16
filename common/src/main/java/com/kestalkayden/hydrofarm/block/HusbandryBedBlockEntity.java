package com.kestalkayden.hydrofarm.block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.HydrofarmRefs;
import com.kestalkayden.hydrofarm.util.AnimalNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Husbandry Bed BE — 2 stalls per block, each can host one captured animal. Animals are
 *  stored as serialized NBT (entity isn't actually in the world); the bed advances each
 *  stall's harvest progress while food is available in the cluster's food pool, producing
 *  renewable drops (wool, eggs) on cycle completion.
 *
 *  <p>Inherits cluster water/XP/item pooling from {@link AbstractClusterBedBlockEntity}.
 *  Food is sourced from the cluster's item pool — pumps feed the bed by depositing wheat
 *  or seeds, and drops are extracted from the same pool. */
public class HusbandryBedBlockEntity extends AbstractClusterBedBlockEntity
        implements AnimalBedHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("hydrofarm-husbandry-bed");

    public static final int STALL_COUNT = 2;
    public static final int STALL_LEFT = 0;
    public static final int STALL_RIGHT = 1;

    /** Per-stall: empty (no animal) or holding a captured animal as serialized NBT. */
    private final Identifier[] hostTypes = new Identifier[STALL_COUNT];
    private final CompoundTag[] hostNbts = new CompoundTag[STALL_COUNT];
    private final int[] harvestProgress = new int[STALL_COUNT];

    /** Cluster-wide per-tick gating snapshot — see {@link #penAggregates}. Cached on the cluster
     *  OWNER bed and shared by every member so the heavy slot scans run once per cluster per tick. */
    private record PenAggregates(Map<Identifier, Integer> adultCounts, boolean hasItemRoom,
                                 Set<Item> presentFoods, int clusterWaterMb) {}
    private long penAggTick = Long.MIN_VALUE;
    private PenAggregates penAgg;

    public HusbandryBedBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.HUSBANDRY_BED_BE.get(), pos, state);
    }

    @Override
    public net.minecraft.world.level.material.Fluid getOutputFluid() {
        // Husbandry's output fluid is milk — produced by cow hosts (1000 mB per cycle) and
        // extractable via the standard liquid pipe/pump network. The slot stays empty when the
        // cluster only has non-fluid-producing species (sheep, chicken).
        return HydrofarmRefs.FLUID_MILK.get();
    }

    /** Feed (wheat/seeds) is locked in — the animals eat it, but it can't be pumped or hoppered
     *  back out. Only the produce (wool/eggs) leaves the bed. */
    @Override
    public boolean canPumpOut(Item item) {
        return !HusbandryAnimal.isFood(item);
    }

    /** One stack of each feed type per bed (scaled by cluster size) is plenty — animals sip ~1 per
     *  cycle, so this lasts ages while stopping an input pump from hoarding thousands of wheat. */
    private static final int FEED_CAP_PER_BED = 64;

    @Override
    public boolean canAccept(Item item) {
        if (!HusbandryAnimal.isFood(item)) return true;
        return countItemInCluster(item) < FEED_CAP_PER_BED * clusterMembers().size();
    }

    // ---- Stall accessors ------------------------------------------------------------------

    @Override
    public int stallCount() { return STALL_COUNT; }

    @Override
    public boolean hasHost(int stall) {
        return stall >= 0 && stall < STALL_COUNT && hostTypes[stall] != null;
    }

    @Override
    public Identifier getHostType(int stall) {
        return (stall >= 0 && stall < STALL_COUNT) ? hostTypes[stall] : null;
    }

    @Override
    public CompoundTag getHostNbt(int stall) {
        return (stall >= 0 && stall < STALL_COUNT) ? hostNbts[stall] : null;
    }

    public int getHarvestProgress(int stall) {
        return (stall >= 0 && stall < STALL_COUNT) ? harvestProgress[stall] : 0;
    }

    /** Returns the index of the first empty stall, or -1 if both are occupied. */
    public int firstEmptyStall() {
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] == null) return s;
        }
        return -1;
    }

    /** Returns the highest-index occupied stall, used by sneak-right-click extraction so
     *  removal mirrors install order (RIGHT extracted first). */
    public int lastOccupiedStall() {
        for (int s = STALL_COUNT - 1; s >= 0; s--) {
            if (hostTypes[s] != null) return s;
        }
        return -1;
    }

    public boolean anyOccupied() {
        for (int s = 0; s < STALL_COUNT; s++) if (hostTypes[s] != null) return true;
        return false;
    }

    /** Drops a captured animal into the given stall. Returns true if installed (stall was
     *  empty and the species is supported). */
    public boolean installHost(int stall, Identifier type, CompoundTag nbt) {
        if (stall < 0 || stall >= STALL_COUNT) return false;
        if (hostTypes[stall] != null) return false;
        if (!HusbandryAnimal.isSupported(type)) return false;
        hostTypes[stall] = type;
        hostNbts[stall] = nbt;
        harvestProgress[stall] = 0;
        setChanged();
        syncToClients();
        return true;
    }

    /** Removes a host without dropping anything — returns the stored (type, nbt) so the
     *  caller can decide what to do (place in a net, spawn as live entity, etc.). */
    public record EvictedHost(Identifier type, CompoundTag nbt) {}

    public EvictedHost evictHost(int stall) {
        if (stall < 0 || stall >= STALL_COUNT) return null;
        if (hostTypes[stall] == null) return null;
        EvictedHost out = new EvictedHost(hostTypes[stall], hostNbts[stall]);
        hostTypes[stall] = null;
        hostNbts[stall] = null;
        harvestProgress[stall] = 0;
        setChanged();
        syncToClients();
        return out;
    }

    // ---- Cluster-wide occupancy (the whole pen is one pool) --------------------------------

    /** Where a cluster install/evict landed, so the caller can place feedback at the bed that
     *  actually received or released the animal (which may be a neighbor of the clicked bed). */
    public record InstallResult(HusbandryBedBlockEntity member, int stall) {}
    public record EvictResult(HusbandryBedBlockEntity member, int stall, EvictedHost host) {}

    /** Installs into the first cluster member (cluster sort order) with a free stall. Returns the
     *  receiving member + stall, or null if every stall across the cluster is occupied. Lets a
     *  player load the whole pen by clicking any single bed. */
    public InstallResult clusterInstallHost(Identifier type, CompoundTag nbt) {
        for (HusbandryBedBlockEntity m : clusterMembersOfType(HusbandryBedBlockEntity.class)) {
            int s = m.firstEmptyStall();
            if (s >= 0 && m.installHost(s, type, nbt)) return new InstallResult(m, s);
        }
        return null;
    }

    /** Cluster-wide LIFO eviction — members scanned in reverse sort order, then that member's
     *  highest occupied stall. Returns the evicted host + its origin, or null if the pen is empty. */
    public EvictResult clusterEvictLast() {
        List<HusbandryBedBlockEntity> members = clusterMembersOfType(HusbandryBedBlockEntity.class);
        for (int i = members.size() - 1; i >= 0; i--) {
            HusbandryBedBlockEntity m = members.get(i);
            int s = m.lastOccupiedStall();
            if (s >= 0) {
                EvictedHost h = m.evictHost(s);
                if (h != null) return new EvictResult(m, s, h);
            }
        }
        return null;
    }

    // ---- Server tick — food consumption + harvest cycle -----------------------------------

    /** Vanilla baby growth: Age starts at -24000, increments by 1 per tick, becomes 0 (adult)
     *  after 20 minutes. We use 4 per tick so an installed baby grows up in ~5 minutes. */
    private static final int GROWTH_PER_TICK = 4;

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        if (!anyOccupied()) return;

        // Cluster-wide gating aggregates, computed ONCE per cluster per tick on the owner bed and
        // shared by every member — collapses the per-bed/per-stall cluster re-scan from
        // O(clusterSize^2) to O(clusterSize) each tick. adultCounts drive diminishing returns (an
        // overstocked species harvests proportionally slower, soft-capping monocultures). hasItemRoom
        // pauses item-producers on a full buffer so a backed-up pen doesn't spill drops to the ground.
        // These are optimistic (water/buffer/food only shrink within a tick, and consumeFood +
        // extractWaterFromCluster are the real gates), so a stale-true at worst costs one retry.
        // Babies still age (above the gate).
        PenAggregates agg = penAggregates(level);
        Map<Identifier, Integer> adultCounts = agg.adultCounts();
        boolean hasRoom = agg.hasItemRoom();

        boolean changed = false;
        boolean anyAdultTransition = false;
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] == null) continue;
            HusbandryAnimal animal = HusbandryAnimal.forEntity(hostTypes[s]);
            if (animal == null) continue;

            // Baby growth gates harvest: a juvenile sheep can't be sheared, a chick can't lay.
            // While Age < 0, we just tick the entity up (no food consumed, no harvest progress)
            // and skip to the next stall. The cycle restarts cleanly once it becomes adult.
            if (AnimalNbt.isBaby(hostNbts[s])) {
                boolean grewUp = AnimalNbt.ageBy(hostNbts[s], GROWTH_PER_TICK);
                changed = true;
                if (grewUp) anyAdultTransition = true;
                continue;
            }

            // Need BOTH food and water in the cluster pool to advance — animals drink too
            // (hydrofarm, after all). Progress ticks up while supply is present; one food plus the
            // species' water amount are consumed together at cycle completion.
            // Only item-producing species (sheep wool / chicken eggs) pause on a full item buffer —
            // a fluid-only producer (cow milk, fluidProducedMb>0) can't overflow it to the ground, so
            // it keeps going (milk just discards harmlessly when the fluid pool is full).
            boolean producesItems = animal.fluidProducedMb == 0;
            if (!hasFood(animal, agg.presentFoods()) || agg.clusterWaterMb() < animal.waterPerCycle
                || (producesItems && !hasRoom)) continue;

            int effCycle = effectiveCycleTicks(animal.cycleTicks,
                adultCounts.getOrDefault(hostTypes[s], 1));

            harvestProgress[s]++;
            changed = true;

            if (harvestProgress[s] >= effCycle) {
                if (consumeFood(animal)) {
                    extractWaterFromCluster(animal.waterPerCycle);
                    produceHarvest(level, pos, animal, hostNbts[s]);
                    harvestProgress[s] = 0;
                } else {
                    // Lost the food race against another stall in the cluster — pause until
                    // next tick. Keep progress at the threshold so we retry immediately.
                    harvestProgress[s] = effCycle;
                }
            }
        }

        if (anyAdultTransition) {
            // Force a chunk-level sync so the client renderer rebuilds its cached entity with
            // the adult model. setChanged alone only marks the BE dirty for save; sendBlockUpdated
            // pushes the new NBT to all watching players.
            setChanged();
            level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        } else if (changed && (level.getGameTime() + phaseOffset(20)) % 20 == 0) {
            // Offset the once-per-second dirty flush per bed so progressing pens don't all call
            // setChanged() on the same global tick.
            setChanged();
        }
    }

    /** Cluster-wide gating snapshot, computed once per tick on the OWNER bed and shared by every
     *  member. Members locate the owner via the cached {@link #findCluster()} (which holds only
     *  member POSITIONS — no block-entity resolution) plus one block-entity lookup, then delegate —
     *  so the owner resolves the member list and runs the heavy slot scan exactly ONCE per cluster
     *  per tick (O(clusterSize)), instead of every bed rebuilding the list and re-scanning (O(N^2)).
     *
     *  <p>Owner = first member in the cluster's deterministic Z,X sort. It's consistent across members
     *  within a tick: the world layout is frozen during a tick, so every member's findCluster() yields
     *  the same sorted members and the same first-LOADED owner instance (a position whose chunk is
     *  unloaded is skipped identically by all, shifting the owner consistently). */
    private PenAggregates penAggregates(ServerLevel level) {
        Cluster cluster = findCluster();
        if (cluster != null && !cluster.members().isEmpty()
                && level.getBlockEntity(cluster.members().get(0)) instanceof HusbandryBedBlockEntity owner) {
            return owner.computePenAggregates(level);
        }
        return computePenAggregates(level);  // singleton fallback (no cluster / owner not loaded)
    }

    /** Memoized on the owner by tick. Resolves the member list on {@code this} (the owner) via the
     *  per-tick-cached {@link #clusterMembers()} and scans it once. The adult-count predicate mirrors
     *  {@link #clusterAdultCounts()} (kept separate for the GUI rate gauges) — keep the two in sync. */
    private PenAggregates computePenAggregates(ServerLevel level) {
        long now = level.getGameTime();
        if (penAgg != null && penAggTick == now) return penAgg;

        Map<Identifier, Integer> adults = new HashMap<>();
        Set<Item> foods = new HashSet<>();
        boolean room = false;
        int waterMb = 0;
        for (AbstractClusterBedBlockEntity mm : clusterMembers()) {
            waterMb += mm.waterMb;   // matches clusterWaterMb(): sum over every member, all subclasses
            if (!(mm instanceof HusbandryBedBlockEntity m)) continue;
            for (int s = 0; s < STALL_COUNT; s++) {
                if (m.hostTypes[s] != null && !AnimalNbt.isBaby(m.hostNbts[s])) {
                    adults.merge(m.hostTypes[s], 1, Integer::sum);
                }
            }
            var its = m.getItems();
            for (int i = 0; i < its.size(); i++) {
                ItemStack stack = its.get(i);
                if (stack.isEmpty()) { room = true; continue; }
                if (stack.getCount() < stack.getMaxStackSize()) room = true;
                if (HusbandryAnimal.isFood(stack.getItem())) foods.add(stack.getItem());
            }
        }
        penAgg = new PenAggregates(adults, room, foods, waterMb);
        penAggTick = now;
        return penAgg;
    }

    /** True if any food present in the cluster (from the per-tick snapshot) feeds {@code animal}. */
    private static boolean hasFood(HusbandryAnimal animal, Set<Item> presentFoods) {
        for (Item f : presentFoods) {
            if (animal.foodAcceptor.test(f)) return true;
        }
        return false;
    }

    private boolean consumeFood(HusbandryAnimal animal) {
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            for (int i = 0; i < m.getItems().size(); i++) {
                ItemStack stack = m.getItems().get(i);
                if (!stack.isEmpty() && animal.foodAcceptor.test(stack.getItem())) {
                    stack.shrink(1);
                    bumpItemRev();   // direct-list mutation — keep the countItemInCluster memo honest
                    m.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    private void produceHarvest(ServerLevel level, BlockPos pos, HusbandryAnimal animal,
                                 CompoundTag hostNbt) {
        List<ItemStack> drops = animal.dropProducer.apply(hostNbt, level.getRandom());
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            recordHarvest(level, drop.getItem(), drop.getCount());
            ItemStack overflow = depositItemIntoCluster(drop);
            if (!overflow.isEmpty()) {
                Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, overflow);
            }
        }
        // Fluid output (e.g., milk for cow) goes to the cluster's output-fluid pool. The fluid
        // identity is whatever getOutputFluid() declares — slot storage is a plain int counter.
        // Overflow (cluster full) silently drops; players who care can attach a drain pump.
        if (animal.fluidProducedMb > 0) {
            depositLiquidXpToCluster(animal.fluidProducedMb);
        }
    }

    // ---- Cluster aggregations (mirrors HydroponicsBed pattern for the GUI eventually) -----

    /** [occupied, total]. Total = clusterSize × STALL_COUNT. */
    public int[] clusterStallCounts() {
        int occupied = 0, total = 0;
        for (HusbandryBedBlockEntity m : clusterMembersOfType(HusbandryBedBlockEntity.class)) {
            for (int s = 0; s < STALL_COUNT; s++) {
                total++;
                if (m.hostTypes[s] != null) occupied++;
            }
        }
        return new int[]{occupied, total};
    }

    /** entityType → count across the cluster. Useful for the "Sheep × 4" summary. */
    public Map<Identifier, Integer> clusterHostCounts() {
        Map<Identifier, Integer> counts = new HashMap<>();
        for (HusbandryBedBlockEntity m : clusterMembersOfType(HusbandryBedBlockEntity.class)) {
            for (int s = 0; s < STALL_COUNT; s++) {
                if (m.hostTypes[s] != null) {
                    counts.merge(m.hostTypes[s], 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** Adults only (babies excluded) — the producing head-count that drives diminishing returns. */
    private Map<Identifier, Integer> clusterAdultCounts() {
        Map<Identifier, Integer> counts = new HashMap<>();
        for (HusbandryBedBlockEntity m : clusterMembersOfType(HusbandryBedBlockEntity.class)) {
            for (int s = 0; s < STALL_COUNT; s++) {
                if (m.hostTypes[s] != null && !AnimalNbt.isBaby(m.hostNbts[s])) {
                    counts.merge(m.hostTypes[s], 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** Stretches a species' harvest cycle by its overstock factor (count / effective count), so an
     *  overstocked species produces proportionally slower. count ≤ 1 → unchanged. */
    private static int effectiveCycleTicks(int baseCycle, int speciesCount) {
        if (speciesCount <= 1) return baseCycle;
        double eff = effectiveSpeciesCount(speciesCount);
        if (eff <= 0) return baseCycle;
        return (int) Math.round(baseCycle * speciesCount / eff);
    }

    /** Analytical cluster-wide gauge rates, mirroring the hydroponics bed: steady-state water
     *  drawn (↓) and milk produced (↑), summed across every housed animal from its per-cycle
     *  figures and cycle length. */
    public int clusterWaterRateMbPerMin() {
        return rateMbPerMin(a -> a.waterPerCycle);
    }

    public int clusterOutFluidRateMbPerMin() {
        return rateMbPerMin(a -> a.fluidProducedMb);
    }

    private int rateMbPerMin(java.util.function.ToIntFunction<HusbandryAnimal> perCycle) {
        double mbPerSec = 0.0;
        for (Map.Entry<Identifier, Integer> e : clusterAdultCounts().entrySet()) {
            HusbandryAnimal a = HusbandryAnimal.forEntity(e.getKey());
            if (a == null || a.cycleTicks <= 0) continue;
            double cycleSec = a.cycleTicks / 20.0;
            // Effective (diminished) producing head-count, mirroring the per-stall cycle stretch.
            mbPerSec += effectiveSpeciesCount(e.getValue()) * (perCycle.applyAsInt(a) / cycleSec);
        }
        return (int) Math.round(mbPerSec * 60.0);
    }

    // ---- Save/load (subclass hooks) -------------------------------------------------------

    @Override
    protected void saveSubclassData(ValueOutput out) {
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] != null) {
                out.putString("HostType_" + s, hostTypes[s].toString());
                // Encode NBT via codec — the ValueOutput child writer mirrors how vanilla
                // persists embedded compound tags within a parent ValueOutput.
                out.store("HostNbt_" + s, CompoundTag.CODEC, hostNbts[s]);
                out.putInt("Progress_" + s, harvestProgress[s]);
            }
        }
    }

    @Override
    protected void loadSubclassData(ValueInput in) {
        for (int s = 0; s < STALL_COUNT; s++) {
            hostTypes[s] = null;
            hostNbts[s] = null;
            harvestProgress[s] = 0;

            var typeOpt = in.getString("HostType_" + s);
            if (typeOpt.isPresent()) {
                try {
                    hostTypes[s] = Identifier.parse(typeOpt.get());
                } catch (Exception ignored) {}
            }
            if (hostTypes[s] != null) {
                var nbtOpt = in.read("HostNbt_" + s, CompoundTag.CODEC);
                if (nbtOpt.isPresent()) hostNbts[s] = nbtOpt.get();
                harvestProgress[s] = in.getIntOr("Progress_" + s, 0);
            }
        }
    }

    @Override
    protected void dropSubclassContents(ServerLevel level, BlockPos pos) {
        // Release each captured animal as a live entity at the bed position. Mirrors
        // standard "break a block that holds an entity, the entity pops out" behavior.
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] == null) continue;
            CompoundTag nbt = hostNbts[s];
            try {
                var in = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), nbt);
                Entity spawned = EntityType.loadEntityRecursive(in, level, EntitySpawnReason.LOAD, e -> {
                    e.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    return e;
                });
                if (spawned != null) level.addFreshEntity(spawned);
            } catch (Throwable t) {
                // If the NBT is malformed (very edge — captured then mod changed), the animal is
                // lost but the rest of the bed cleanup proceeds. Rare break-time path, so log
                // every time: a silently vanishing animal was undiagnosable.
                LOGGER.warn("Could not release captured {} at {} — entity NBT failed to load",
                    hostTypes[s], pos, t);
            }
            hostTypes[s] = null;
            hostNbts[s] = null;
            harvestProgress[s] = 0;
        }
    }
}
