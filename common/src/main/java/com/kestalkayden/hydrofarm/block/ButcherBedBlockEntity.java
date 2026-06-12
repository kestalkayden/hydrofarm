package com.kestalkayden.hydrofarm.block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kestalkayden.hydrofarm.HydrofarmConfig;
import com.kestalkayden.hydrofarm.HydrofarmRefs;
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

/** Butcher Bed BE — 2 stalls per block, each hosting a captured animal as permanent breeding
 *  stock. The animals are NEVER consumed: a species with 2+ adults in the cluster yields that
 *  species' slaughter loot on a recurring cycle (rolled for variance), abstractly representing
 *  butchered offspring while the breeding stock itself stays put. This makes the pen self-sustaining
 *  by construction — it can't empty itself — and removes the old literal cull-and-breed simulation.
 *
 *  <p>Output scales per breeding pair (floor(count/2) of a species) and is delivered as a steady
 *  drip: each pair's half-length cycle is spread across the pairs, so a stocked pen trickles drops
 *  out continuously rather than dumping a synchronized burst. A lone animal (no pair) produces
 *  nothing. Each drop still consumes one feed item + the species' per-cycle water. */
public class ButcherBedBlockEntity extends AbstractClusterBedBlockEntity
        implements AnimalBedHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("hydrofarm-butcher-bed");

    public static final int STALL_COUNT = 2;
    public static final int STALL_LEFT = 0;
    public static final int STALL_RIGHT = 1;

    /** Baby growth per tick (same 4x rate as husbandry). Captured babies age to adults before they
     *  count toward a breeding pair; they never produce while young. */
    private static final int GROWTH_PER_TICK = 4;

    private final Identifier[] hostTypes = new Identifier[STALL_COUNT];
    private final CompoundTag[] hostNbts = new CompoundTag[STALL_COUNT];

    public ButcherBedBlockEntity(BlockPos pos, BlockState state) {
        super(HydrofarmRefs.BUTCHER_BED_BE.get(), pos, state);
    }

    @Override
    public net.minecraft.world.level.material.Fluid getOutputFluid() {
        // Animal kills produce vanilla XP; the butcher bed channels that into the
        // shared liquid_xp pool. Same slot semantics as hydroponics.
        return HydrofarmRefs.LIQUID_XP.get();
    }

    @Override
    public boolean outputFluidEnabled() {
        return HydrofarmConfig.butcherBedXp();
    }

    /** Feed is locked in — the animals eat it, but it can't be pumped or hoppered back out. Only
     *  the produced drops leave the bed. */
    @Override
    public boolean canPumpOut(Item item) {
        return !ButcherAnimal.isFood(item);
    }

    /** One stack of each feed type per bed (scaled by cluster size) is plenty — animals sip ~1 per
     *  cycle, so this lasts ages while stopping an input pump from hoarding thousands of feed. */
    private static final int FEED_CAP_PER_BED = 64;

    @Override
    public boolean canAccept(Item item) {
        if (!ButcherAnimal.isFood(item)) return true;
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

    public int firstEmptyStall() {
        for (int s = 0; s < STALL_COUNT; s++) if (hostTypes[s] == null) return s;
        return -1;
    }

    public int lastOccupiedStall() {
        for (int s = STALL_COUNT - 1; s >= 0; s--) if (hostTypes[s] != null) return s;
        return -1;
    }

    public boolean anyOccupied() {
        for (int s = 0; s < STALL_COUNT; s++) if (hostTypes[s] != null) return true;
        return false;
    }

    public boolean installHost(int stall, Identifier type, CompoundTag nbt) {
        if (stall < 0 || stall >= STALL_COUNT) return false;
        if (hostTypes[stall] != null) return false;
        if (!ButcherAnimal.isSupported(type)) return false;
        hostTypes[stall] = type;
        hostNbts[stall] = nbt;
        setChanged();
        syncToClients();
        return true;
    }

    public record EvictedHost(Identifier type, CompoundTag nbt) {}

    public EvictedHost evictHost(int stall) {
        if (stall < 0 || stall >= STALL_COUNT) return null;
        if (hostTypes[stall] == null) return null;
        EvictedHost out = new EvictedHost(hostTypes[stall], hostNbts[stall]);
        hostTypes[stall] = null;
        hostNbts[stall] = null;
        setChanged();
        syncToClients();
        return out;
    }

    // ---- Cluster-wide occupancy (the whole pen is one pool) --------------------------------

    /** Where a cluster install/evict landed, so the caller can place feedback at the bed that
     *  actually received or released the animal (which may be a neighbor of the clicked bed). */
    public record InstallResult(ButcherBedBlockEntity member, int stall) {}
    public record EvictResult(ButcherBedBlockEntity member, int stall, EvictedHost host) {}

    /** Installs into the first cluster member (cluster sort order) with a free stall. Returns the
     *  receiving member + stall, or null if every stall across the cluster is occupied. Lets a
     *  player load the whole pen by clicking any single bed. */
    public InstallResult clusterInstallHost(Identifier type, CompoundTag nbt) {
        for (ButcherBedBlockEntity m : clusterMembersOfType(ButcherBedBlockEntity.class)) {
            int s = m.firstEmptyStall();
            if (s >= 0 && m.installHost(s, type, nbt)) return new InstallResult(m, s);
        }
        return null;
    }

    /** Cluster-wide LIFO eviction — members scanned in reverse sort order, then that member's
     *  highest occupied stall. Returns the evicted host + its origin, or null if the pen is empty. */
    public EvictResult clusterEvictLast() {
        List<ButcherBedBlockEntity> members = clusterMembersOfType(ButcherBedBlockEntity.class);
        for (int i = members.size() - 1; i >= 0; i--) {
            ButcherBedBlockEntity m = members.get(i);
            int s = m.lastOccupiedStall();
            if (s >= 0) {
                EvictedHost h = m.evictHost(s);
                if (h != null) return new EvictResult(m, s, h);
            }
        }
        return null;
    }

    // ---- Server tick — baby growth + cluster-wide per-pair production ----------------------

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        // Per bed: grow any captured babies toward adulthood (they don't produce until grown).
        boolean transition = false;
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] == null) continue;
            if (isBaby(hostNbts[s]) && ageBy(hostNbts[s], GROWTH_PER_TICK)) transition = true;
        }
        if (transition) {
            setChanged();
            syncToClients();
        }

        // Cluster-wide production runs on the cluster owner only, so it fires once per pen rather
        // than once per bed. Cluster.members() is deterministic-sorted by Z,X.
        Cluster cluster = findCluster();
        if (cluster == null || cluster.members().isEmpty()
                || !cluster.members().get(0).equals(worldPosition)) {
            return;
        }
        produceClusterLoot(level, pos);
    }

    /** For each species with a breeding pair (2+ adults), emit its slaughter loot on a steady drip:
     *  half the species' cull cycle, spread evenly across the number of pairs. Each emission costs
     *  one feed item + the species' per-cycle water; without either, the pen idles that species. */
    private void produceClusterLoot(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        for (Map.Entry<Identifier, Integer> e : clusterAdultCounts().entrySet()) {
            int adults = e.getValue();
            if (adults < 2) continue;                     // need a breeding pair to sustain output
            ButcherAnimal animal = ButcherAnimal.forEntity(e.getKey());
            if (animal == null || animal.cycleTicks <= 0) continue;

            // Per-species diminishing returns: effective pairs flatten as the herd grows, so a
            // mega-monoculture soft-caps. interval lengthens accordingly (slower drip).
            double effectivePairs = effectiveSpeciesCount(adults) / 2.0;
            if (effectivePairs <= 0) continue;
            int interval = Math.max(1, (int) Math.round((animal.cycleTicks / 2.0) / effectivePairs));
            // Per-pen phase offset (owner position) so identical same-species pens don't all emit on
            // the same global tick; spreads the cull drip across clusters.
            if ((now + phaseOffset(interval)) % interval != 0) continue;

            // Storage full → skip this species' slaughter (and its food/water spend) so overflow
            // drops don't spill to the ground as item entities; retries next interval.
            if (!hasFoodInCluster(animal) || clusterWaterMb() < animal.waterPerCycle || !clusterHasItemRoom()) continue;
            if (!consumeFood(animal)) continue;
            extractWaterFromCluster(animal.waterPerCycle);
            emitLoot(level, pos, animal);
        }
    }

    /** Rolls one slaughter's worth of drops for {@code animal} (variance comes from the per-species
     *  drop producer) into the cluster pool, plus its cull XP. The breeding stock is untouched. */
    private void emitLoot(ServerLevel level, BlockPos pos, ButcherAnimal animal) {
        List<ItemStack> drops = animal.dropProducer.apply(null, level.getRandom());
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            recordHarvest(level, drop.getItem(), drop.getCount());
            ItemStack overflow = depositItemIntoCluster(drop);
            if (!overflow.isEmpty()) {
                Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, overflow);
            }
        }
        if (animal.xpProducedMb > 0) {
            depositLiquidXpToCluster(animal.xpProducedMb);
        }
    }

    /** True if the host NBT represents a baby (Age &lt; 0). */
    private static boolean isBaby(CompoundTag nbt) {
        if (nbt == null || !nbt.contains("Age")) return false;
        return nbt.getIntOr("Age", 0) < 0;
    }

    /** Increment the host's Age field by {@code delta}, capped at 0. Returns true if this tick
     *  is the one that brings the host from baby to adult. */
    private static boolean ageBy(CompoundTag nbt, int delta) {
        if (nbt == null || !nbt.contains("Age")) return false;
        int oldAge = nbt.getIntOr("Age", 0);
        if (oldAge >= 0) return false;
        int newAge = Math.min(0, oldAge + delta);
        nbt.putInt("Age", newAge);
        return newAge >= 0;
    }

    private boolean hasFoodInCluster(ButcherAnimal animal) {
        for (AbstractClusterBedBlockEntity m : clusterMembers()) {
            for (int i = 0; i < m.getItems().size(); i++) {
                ItemStack stack = m.getItems().get(i);
                if (!stack.isEmpty() && animal.foodAcceptor.test(stack.getItem())) return true;
            }
        }
        return false;
    }

    private boolean consumeFood(ButcherAnimal animal) {
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

    // ---- Cluster aggregations -------------------------------------------------------------

    public int[] clusterStallCounts() {
        int occupied = 0, total = 0;
        for (ButcherBedBlockEntity m : clusterMembersOfType(ButcherBedBlockEntity.class)) {
            for (int s = 0; s < STALL_COUNT; s++) {
                total++;
                if (m.hostTypes[s] != null) occupied++;
            }
        }
        return new int[]{occupied, total};
    }

    public Map<Identifier, Integer> clusterHostCounts() {
        Map<Identifier, Integer> counts = new HashMap<>();
        for (ButcherBedBlockEntity m : clusterMembersOfType(ButcherBedBlockEntity.class)) {
            for (int s = 0; s < STALL_COUNT; s++) {
                if (m.hostTypes[s] != null) counts.merge(m.hostTypes[s], 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Adults only (babies excluded) — drives the breeding-pair production count. Iterates the
     *  per-tick-cached {@link #clusterMembers()} (cluster discovery is already block-typed, so the
     *  instanceof always holds) and allocates the count map lazily — an unstocked or all-baby pen
     *  returns the shared empty map, so the once-per-tick owner call allocates nothing when idle. */
    private Map<Identifier, Integer> clusterAdultCounts() {
        Map<Identifier, Integer> counts = null;
        for (AbstractClusterBedBlockEntity mm : clusterMembers()) {
            if (!(mm instanceof ButcherBedBlockEntity m)) continue;
            for (int s = 0; s < STALL_COUNT; s++) {
                if (m.hostTypes[s] != null && !isBaby(m.hostNbts[s])) {
                    if (counts == null) counts = new HashMap<>();
                    counts.merge(m.hostTypes[s], 1, Integer::sum);
                }
            }
        }
        return counts == null ? Map.of() : counts;
    }

    /** Analytical cluster-wide gauge rates, mirroring the hydroponics bed: steady-state water
     *  drawn (↓) and liquid XP produced (↑). Production is per breeding pair at half the cull
     *  cycle, which works out to the same throughput as the old per-animal/full-cycle model —
     *  so the per-animal figures below still reflect the real steady-state rate. */
    public int clusterWaterRateMbPerMin() {
        return rateMbPerMin(a -> a.waterPerCycle);
    }

    public int clusterOutFluidRateMbPerMin() {
        if (!outputFluidEnabled()) return 0;
        return rateMbPerMin(a -> a.xpProducedMb);
    }

    private int rateMbPerMin(java.util.function.ToIntFunction<ButcherAnimal> perCycle) {
        double mbPerSec = 0.0;
        for (Map.Entry<Identifier, Integer> e : clusterAdultCounts().entrySet()) {
            int adults = e.getValue();
            if (adults < 2) continue;
            ButcherAnimal a = ButcherAnimal.forEntity(e.getKey());
            if (a == null || a.cycleTicks <= 0) continue;
            // Mirror the diminishing-returns production: effective pairs emissions per half-cycle.
            double effectivePairs = effectiveSpeciesCount(adults) / 2.0;
            double emitsPerSec = effectivePairs / ((a.cycleTicks / 2.0) / 20.0);
            mbPerSec += emitsPerSec * perCycle.applyAsInt(a);
        }
        return (int) Math.round(mbPerSec * 60.0);
    }

    // ---- Save/load (subclass hooks) -------------------------------------------------------

    @Override
    protected void saveSubclassData(ValueOutput out) {
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] != null) {
                out.putString("HostType_" + s, hostTypes[s].toString());
                out.store("HostNbt_" + s, CompoundTag.CODEC, hostNbts[s]);
            }
        }
    }

    @Override
    protected void loadSubclassData(ValueInput in) {
        for (int s = 0; s < STALL_COUNT; s++) {
            hostTypes[s] = null;
            hostNbts[s] = null;

            var typeOpt = in.getString("HostType_" + s);
            if (typeOpt.isPresent()) {
                try {
                    hostTypes[s] = Identifier.parse(typeOpt.get());
                } catch (Exception ignored) {}
            }
            if (hostTypes[s] != null) {
                var nbtOpt = in.read("HostNbt_" + s, CompoundTag.CODEC);
                if (nbtOpt.isPresent()) hostNbts[s] = nbtOpt.get();
            }
        }
    }

    @Override
    protected void dropSubclassContents(ServerLevel level, BlockPos pos) {
        // Release each captured animal as a live entity at the bed position. Breaking the bed
        // sets the animals free rather than culling them.
        for (int s = 0; s < STALL_COUNT; s++) {
            if (hostTypes[s] == null) continue;
            CompoundTag nbt = hostNbts[s];
            try {
                Entity spawned = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> {
                    e.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    return e;
                });
                if (spawned != null) level.addFreshEntity(spawned);
            } catch (Throwable t) {
                // Malformed NBT (e.g. captured, then the providing mod changed) — the animal is
                // lost but the bed cleanup proceeds. Rare break-time path, so log every time:
                // a silently vanishing animal was undiagnosable.
                LOGGER.warn("Could not release captured {} at {} — entity NBT failed to load",
                    hostTypes[s], pos, t);
            }
            hostTypes[s] = null;
            hostNbts[s] = null;
        }
    }
}
