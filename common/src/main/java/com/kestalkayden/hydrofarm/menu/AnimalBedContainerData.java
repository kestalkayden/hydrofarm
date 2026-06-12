package com.kestalkayden.hydrofarm.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.kestalkayden.hydrofarm.block.AbstractClusterBedBlockEntity;
import com.kestalkayden.hydrofarm.block.ButcherAnimal;
import com.kestalkayden.hydrofarm.block.ButcherBedBlockEntity;
import com.kestalkayden.hydrofarm.block.HusbandryAnimal;
import com.kestalkayden.hydrofarm.block.HusbandryBedBlockEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;

/** ContainerData snapshot for the Animal Bed menu — works with either husbandry or butcher
 *  beds. Refreshes once per second to keep the cluster aggregation cost amortized across the
 *  many per-tick broadcasts the menu does. */
public class AnimalBedContainerData implements ContainerData {

    private static final int SAMPLE_INTERVAL_TICKS = 20;

    private final AbstractClusterBedBlockEntity be;
    private final int[] snapshot = new int[AnimalBedMenu.DATA_COUNT];
    private long lastSampleTick = Long.MIN_VALUE;

    public AnimalBedContainerData(AbstractClusterBedBlockEntity be) {
        this.be = be;
    }

    private void refreshIfStale() {
        if (be == null) return;
        long now = (be.getLevel() instanceof ServerLevel sl) ? sl.getGameTime() : -1;
        if (now < 0) return;
        if (lastSampleTick != Long.MIN_VALUE && (now - lastSampleTick) < SAMPLE_INTERVAL_TICKS) return;
        lastSampleTick = now;

        snapshot[AnimalBedMenu.DATA_CLUSTER_SIZE]  = be.clusterMembers().size();
        snapshot[AnimalBedMenu.DATA_WATER_MB]      = be.clusterWaterMb();
        snapshot[AnimalBedMenu.DATA_WATER_CAP]     = be.clusterWaterCapMb();
        snapshot[AnimalBedMenu.DATA_OUT_FLUID_MB]  = be.clusterXpMb();
        snapshot[AnimalBedMenu.DATA_OUT_FLUID_CAP] = be.clusterXpCapMb();
        // A config-disabled output (e.g. butcher XP off) syncs as the EMPTY fluid id — the
        // screen hides the gauge on that. Server-side decision, so dedicated servers win.
        snapshot[AnimalBedMenu.DATA_OUT_FLUID_ID]  = be.outputFluidEnabled()
            ? BuiltInRegistries.FLUID.getId(be.getOutputFluid())
            : BuiltInRegistries.FLUID.getId(Fluids.EMPTY);

        // Stall counts + species + food status — sourced from whichever subclass.
        Map<Identifier, Integer> speciesCounts;
        int[] stalls;
        if (be instanceof HusbandryBedBlockEntity hb) {
            stalls = hb.clusterStallCounts();
            speciesCounts = hb.clusterHostCounts();
            snapshot[AnimalBedMenu.DATA_WATER_RATE]     = hb.clusterWaterRateMbPerMin();
            snapshot[AnimalBedMenu.DATA_OUT_FLUID_RATE] = hb.clusterOutFluidRateMbPerMin();
        } else if (be instanceof ButcherBedBlockEntity bb) {
            stalls = bb.clusterStallCounts();
            speciesCounts = bb.clusterHostCounts();
            snapshot[AnimalBedMenu.DATA_WATER_RATE]     = bb.clusterWaterRateMbPerMin();
            snapshot[AnimalBedMenu.DATA_OUT_FLUID_RATE] = bb.clusterOutFluidRateMbPerMin();
        } else {
            stalls = new int[]{0, 0};
            speciesCounts = Map.of();
        }
        snapshot[AnimalBedMenu.DATA_STALLS_OCCUPIED] = stalls[0];
        snapshot[AnimalBedMenu.DATA_STALLS_TOTAL]    = stalls[1];

        writeSpeciesAndFoodMask(speciesCounts);
        writeOutputs(be.clusterHarvestRate());
    }

    private void writeSpeciesAndFoodMask(Map<Identifier, Integer> counts) {
        List<Map.Entry<Identifier, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<Identifier, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int topN = Math.min(AnimalBedMenu.SPECIES_SLOT_COUNT, sorted.size());
        int foodMask = 0;
        for (int i = 0; i < AnimalBedMenu.SPECIES_SLOT_COUNT; i++) {
            int idIdx = AnimalBedMenu.DATA_SPECIES_BASE + i * 2;
            int countIdx = idIdx + 1;
            if (i < topN) {
                Identifier id = sorted.get(i).getKey();
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
                snapshot[idIdx] = type == null ? 0 : BuiltInRegistries.ENTITY_TYPE.getId(type);
                snapshot[countIdx] = sorted.get(i).getValue();
                if (!hasFoodForSpecies(id)) foodMask |= (1 << i);
            } else {
                snapshot[idIdx] = 0;
                snapshot[countIdx] = 0;
            }
        }
        snapshot[AnimalBedMenu.DATA_FOOD_MISSING_MASK] = foodMask;
    }

    private boolean hasFoodForSpecies(Identifier entityType) {
        Predicate<Item> acceptor = null;
        if (be instanceof HusbandryBedBlockEntity) {
            HusbandryAnimal a = HusbandryAnimal.forEntity(entityType);
            if (a != null) acceptor = a.foodAcceptor;
        } else if (be instanceof ButcherBedBlockEntity) {
            ButcherAnimal a = ButcherAnimal.forEntity(entityType);
            if (a != null) acceptor = a.foodAcceptor;
        }
        if (acceptor == null) return true; // Unknown species — don't warn (conservative).
        for (AbstractClusterBedBlockEntity m : be.clusterMembers()) {
            for (int i = 0; i < m.getItems().size(); i++) {
                ItemStack stack = m.getItems().get(i);
                if (!stack.isEmpty() && acceptor.test(stack.getItem())) return true;
            }
        }
        return false;
    }

    private void writeOutputs(Map<Item, Integer> harvests) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(harvests.entrySet());
        sorted.sort(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int topN = Math.min(AnimalBedMenu.OUTPUT_SLOT_COUNT, sorted.size());
        for (int i = 0; i < AnimalBedMenu.OUTPUT_SLOT_COUNT; i++) {
            int idIdx = AnimalBedMenu.DATA_OUTPUT_BASE + i * 2;
            int rateIdx = idIdx + 1;
            if (i < topN) {
                snapshot[idIdx] = BuiltInRegistries.ITEM.getId(sorted.get(i).getKey());
                snapshot[rateIdx] = sorted.get(i).getValue();
            } else {
                snapshot[idIdx] = 0;
                snapshot[rateIdx] = 0;
            }
        }
        snapshot[AnimalBedMenu.DATA_OUTPUT_OVERFLOW] = Math.max(0, sorted.size() - AnimalBedMenu.OUTPUT_SLOT_COUNT);
    }

    @Override
    public int get(int idx) {
        refreshIfStale();
        if (idx < 0 || idx >= snapshot.length) return 0;
        return snapshot[idx];
    }

    @Override
    public void set(int idx, int value) { /* read-only */ }

    @Override
    public int getCount() { return AnimalBedMenu.DATA_COUNT; }
}
