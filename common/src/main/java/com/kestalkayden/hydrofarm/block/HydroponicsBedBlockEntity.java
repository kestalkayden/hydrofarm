package com.kestalkayden.hydrofarm.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.kestalkayden.hydrofarm.HydrofarmConfig;
import com.kestalkayden.hydrofarm.HydrofarmRefs;

/** Hydroponics bed BE — 4 installable planter quadrants per block. Each quadrant has its own
 *  configured seed + growth stage and runs its own growth tick. Inherits cluster water/XP/item
 *  pooling from {@link AbstractClusterBedBlockEntity}. The menu is opened by the block via the
 *  platform MenuApi seam. */
public class HydroponicsBedBlockEntity extends AbstractClusterBedBlockEntity {

    public static final int QUADRANT_COUNT = 4;
    public static final int QUADRANT_NW = 0;
    public static final int QUADRANT_NE = 1;
    public static final int QUADRANT_SW = 2;
    public static final int QUADRANT_SE = 3;

    public static final int GROWTH_INTERVAL_TICKS = 800;
    public static final int WATER_PER_GROW_MB = 15;
    public static final int XP_PER_HARVEST_MB = 1;
    /** Surplus seeds: ~1 in 5 of a seed-bearing crop's seed drop is kept as output (automatable
     *  chicken feed); the rest is discarded since the planter replants itself. */
    public static final int SEED_KEEP_DENOM = 5;

    private final boolean[] installed = new boolean[QUADRANT_COUNT];
    private final Item[] seeds = new Item[]{Items.AIR, Items.AIR, Items.AIR, Items.AIR};
    private final int[] growths = new int[QUADRANT_COUNT];

    public HydroponicsBedBlockEntity(BlockPos pos, BlockState state) {
        this(HydrofarmRefs.HYDROPONICS_BED_BE.get(), pos, state);
    }

    /** Subclass-friendly constructor. The tree farm bed reuses this BE class but registers
     *  its own BlockEntityType so cluster discovery scopes per bed-type. */
    protected HydroponicsBedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ---- Quadrant state -------------------------------------------------------------------

    public boolean isInstalled(int q) { return q >= 0 && q < QUADRANT_COUNT && installed[q]; }
    public Item getSeed(int q) { return (q >= 0 && q < QUADRANT_COUNT) ? seeds[q] : Items.AIR; }
    public int getGrowth(int q) { return (q >= 0 && q < QUADRANT_COUNT) ? growths[q] : 0; }
    public boolean hasSeed(int q) { return isInstalled(q) && seeds[q] != Items.AIR; }

    public boolean anyInstalled() {
        for (boolean b : installed) if (b) return true;
        return false;
    }

    public int firstEmptyQuadrant() {
        for (int q = 0; q < QUADRANT_COUNT; q++) if (!installed[q]) return q;
        return -1;
    }

    public int firstUnconfiguredInstalledQuadrant() {
        for (int q = 0; q < QUADRANT_COUNT; q++) {
            if (installed[q] && seeds[q] == Items.AIR) return q;
        }
        return -1;
    }

    public int firstInstalledQuadrant() {
        for (int q = 0; q < QUADRANT_COUNT; q++) if (installed[q]) return q;
        return -1;
    }

    public int lastInstalledQuadrant() {
        for (int q = QUADRANT_COUNT - 1; q >= 0; q--) {
            if (installed[q]) return q;
        }
        return -1;
    }

    public boolean uninstallPlanter(int q) {
        if (q < 0 || q >= QUADRANT_COUNT || !installed[q]) return false;
        installed[q] = false;
        seeds[q] = Items.AIR;
        growths[q] = 0;
        setChanged();
        syncBlockState();
        return true;
    }

    public void installPlanter(int q) {
        if (q < 0 || q >= QUADRANT_COUNT || installed[q]) return;
        installed[q] = true;
        setChanged();
        syncBlockState();
    }

    public void configureSeed(int q, Item item) {
        if (q < 0 || q >= QUADRANT_COUNT || !installed[q]) return;
        if (item == null) item = Items.AIR;
        seeds[q] = item;
        growths[q] = 0;
        setChanged();
        syncBlockState();
    }

    public static int quadrantFromHit(BlockHitResult hit, BlockPos pos) {
        Vec3 loc = hit.getLocation();
        double dx = loc.x - pos.getX();
        double dz = loc.z - pos.getZ();
        boolean east  = dx >= 0.5;
        boolean south = dz >= 0.5;
        return (south ? QUADRANT_SW : QUADRANT_NW) + (east ? 1 : 0);
    }

    private void syncBlockState() {
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        boolean changed = false;
        for (int q = 0; q < QUADRANT_COUNT; q++) {
            BooleanProperty prop = HydroponicsBedBlock.quadrantProperty(q);
            if (prop != null && state.hasProperty(prop) && state.getValue(prop) != installed[q]) {
                state = state.setValue(prop, installed[q]);
                changed = true;
            }
        }
        // UPDATE_CLIENTS: quadrant flags drive the model + BE render only — no neighbor reacts.
        // setBlock already routes through sendBlockUpdated internally, so the explicit same-state
        // send (which re-pushes the BE data) is only needed when NO state change happened.
        if (changed) {
            level.setBlock(getBlockPos(), state, Block.UPDATE_CLIENTS);
        } else {
            level.sendBlockUpdated(getBlockPos(), state, state, Block.UPDATE_CLIENTS);
        }
        level.getLightEngine().checkBlock(getBlockPos());
    }

    @Override
    public Fluid getOutputFluid() {
        return HydrofarmRefs.LIQUID_XP.get();
    }

    @Override
    public boolean outputFluidEnabled() {
        return HydrofarmConfig.hydroponicsBedXp();
    }

    // ---- Server tick — growth loop per quadrant -------------------------------------------

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        if (!anyInstalled()) return;
        // Per-bed phase offset so a large farm's growth/harvest work doesn't all land on the same
        // global tick (a 40s-periodic spike) — it spreads evenly across the 800-tick window instead.
        if ((level.getGameTime() + phaseOffset(GROWTH_INTERVAL_TICKS)) % GROWTH_INTERVAL_TICKS != 0) return;

        boolean changed = false;
        for (int q = 0; q < QUADRANT_COUNT; q++) {
            if (!installed[q] || seeds[q] == Items.AIR) continue;
            CropAdapter ca = CropAdapter.resolve(seeds[q]);
            if (ca == null) continue;

            int steps = ca.growthSteps();

            if (growths[q] < 0) {
                growths[q] = 0;
                changed = true;
                continue;
            }

            if (growths[q] >= steps) {
                if (harvestQuadrant(level, pos, q, ca)) changed = true;  // paused (full) → no-op, skip the client packet
                continue;
            }

            int consumed = extractWaterFromCluster(WATER_PER_GROW_MB);
            if (consumed >= WATER_PER_GROW_MB) {
                growths[q]++;
                changed = true;
            }
        }

        if (changed) {
            setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Harvests the ready quadrant {@code q}. Returns false (a no-op) when cluster storage is full —
     *  the crop stays at its ready value (growthSteps) and retries next interval instead of
     *  overflowing to the ground. */
    private boolean harvestQuadrant(ServerLevel level, BlockPos pos, int q, CropAdapter ca) {
        // Storage full → don't harvest at all. growths[q] stays at its ready value (the crop sits
        // "ready") and we retry next interval, instead of producing drops that overflow to the
        // ground as item entities. Also correctly defers this cycle's XP deposit.
        if (!clusterHasItemRoom()) return false;

        List<ItemStack> drops;
        if (ca.category() == CropAdapter.Category.CROP && ca.harvestItem() != Items.AIR) {
            // Normalized crop: exactly 1 of the product, deterministic — no per-harvest loot eval.
            drops = List.of(new ItemStack(ca.harvestItem(), 1));
        } else {
            // Trees (bulk logs+sapling) and modded crops without an explicit product: sample the
            // loot table, then discard the surplus-seed half (the planter replants itself).
            drops = filterSurplusSeeds(level, ca.produceDrops(level, pos), seeds[q]);
        }

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            recordHarvest(level, drop.getItem(), drop.getCount());
            ItemStack overflow = depositItemIntoCluster(drop);
            if (!overflow.isEmpty()) {
                Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, overflow);
            }
        }

        depositLiquidXpToCluster(XP_PER_HARVEST_MB);
        growths[q] = 0;
        return true;
    }

    /** Discards the surplus-seed half of a loot-sampled drop list: when the table yields a non-seed
     *  item, keep only ~1/{@link #SEED_KEEP_DENOM} of the matching-seed drops (the planter replants
     *  itself). Used by the tree + modded-crop fallback path; normalized crops bypass this. */
    private List<ItemStack> filterSurplusSeeds(ServerLevel level, List<ItemStack> drops, Item seed) {
        boolean hasNonSeedDrop = false;
        for (ItemStack drop : drops) {
            if (drop.getItem() != seed) { hasNonSeedDrop = true; break; }
        }
        if (!hasNonSeedDrop) return drops;

        List<ItemStack> kept = new ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            if (drop.getItem() != seed) { kept.add(drop); continue; }
            int keep = 0;
            for (int n = 0; n < drop.getCount(); n++) {
                if (level.getRandom().nextInt(SEED_KEEP_DENOM) == 0) keep++;
            }
            if (keep > 0) kept.add(drop.copyWithCount(keep));
        }
        return kept;
    }

    // ---- Hydroponics-specific cluster aggregations ----------------------------------------

    /** Snapshot of planter status totaled across the cluster: [installed, configured,
     *  growing (configured & growth<growthSteps), ready (configured & growth>=growthSteps)]. */
    public int[] clusterPlanterCounts() {
        int installedTotal = 0, configuredTotal = 0, growingTotal = 0, readyTotal = 0;
        for (HydroponicsBedBlockEntity m : clusterMembersOfType(HydroponicsBedBlockEntity.class)) {
            for (int q = 0; q < QUADRANT_COUNT; q++) {
                if (!m.installed[q]) continue;
                installedTotal++;
                if (m.seeds[q] == Items.AIR) continue;
                configuredTotal++;
                CropAdapter ca = CropAdapter.resolve(m.seeds[q]);
                if (ca == null) continue;
                if (m.growths[q] >= ca.growthSteps()) readyTotal++;
                else growingTotal++;
            }
        }
        return new int[]{installedTotal, configuredTotal, growingTotal, readyTotal};
    }

    public int clusterWaterRateMbPerMin() {
        double mbPerSec = 0.0;
        for (HydroponicsBedBlockEntity m : clusterMembersOfType(HydroponicsBedBlockEntity.class)) {
            for (int q = 0; q < QUADRANT_COUNT; q++) {
                if (!m.installed[q] || m.seeds[q] == Items.AIR) continue;
                CropAdapter ca = CropAdapter.resolve(m.seeds[q]);
                if (ca == null) continue;
                int steps = ca.growthSteps();
                double cycleSec = (steps + 1) * GROWTH_INTERVAL_TICKS / 20.0;
                mbPerSec += (steps * WATER_PER_GROW_MB) / cycleSec;
            }
        }
        return (int) Math.round(mbPerSec * 60.0);
    }

    public int clusterXpRateMbPerMin() {
        if (!outputFluidEnabled()) return 0;
        double mbPerSec = 0.0;
        for (HydroponicsBedBlockEntity m : clusterMembersOfType(HydroponicsBedBlockEntity.class)) {
            for (int q = 0; q < QUADRANT_COUNT; q++) {
                if (!m.installed[q] || m.seeds[q] == Items.AIR) continue;
                CropAdapter ca = CropAdapter.resolve(m.seeds[q]);
                if (ca == null) continue;
                int steps = ca.growthSteps();
                double cycleSec = (steps + 1) * GROWTH_INTERVAL_TICKS / 20.0;
                mbPerSec += XP_PER_HARVEST_MB / cycleSec;
            }
        }
        return (int) Math.round(mbPerSec * 60.0);
    }

    // ---- Save/load (subclass hooks) -------------------------------------------------------

    @Override
    protected void saveSubclassData(ValueOutput out) {
        for (int q = 0; q < QUADRANT_COUNT; q++) {
            out.putBoolean("Installed_" + q, installed[q]);
            out.putString("Seed_" + q, BuiltInRegistries.ITEM.getKey(seeds[q]).toString());
            out.putInt("Growth_" + q, growths[q]);
        }
    }

    @Override
    protected void loadSubclassData(ValueInput in) {
        for (int q = 0; q < QUADRANT_COUNT; q++) {
            installed[q] = in.getBooleanOr("Installed_" + q, false);
            if (in.getString("Seed_" + q).isPresent()) {
                seeds[q] = parseItemId(in.getString("Seed_" + q).get());
            } else {
                seeds[q] = Items.AIR;
            }
            growths[q] = in.getIntOr("Growth_" + q, 0);
        }
    }

    @Override
    protected void dropSubclassContents(ServerLevel level, BlockPos pos) {
        // Refund installed planter modules — drop one item entity per installed quadrant at
        // THIS bed's position. They never belonged in the cluster inventory pool; routing
        // them through neighbor.depositItemIntoCluster would let item pumps suction them out
        // alongside crops.
        for (int q = 0; q < QUADRANT_COUNT; q++) {
            if (installed[q]) {
                Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new ItemStack(HydrofarmRefs.HYDROFARM_PLANTER_ITEM.get()));
                installed[q] = false;
                seeds[q] = Items.AIR;
                growths[q] = 0;
            }
        }
    }

    private static Item parseItemId(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) return Items.AIR;
        try {
            Identifier id = Identifier.fromNamespaceAndPath(s.substring(0, colon), s.substring(colon + 1));
            return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;
        } catch (Exception ignored) {
            return Items.AIR;
        }
    }
}
