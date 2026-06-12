package com.kestalkayden.hydrofarm.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.kestalkayden.hydrofarm.block.HydroponicsBedBlockEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;

/** ContainerData implementation that exposes cluster-aggregated stats. Snapshot refreshes
 *  every {@link #SAMPLE_INTERVAL_TICKS} ticks (1 Hz) to keep the aggregation cost amortized
 *  across menu broadcast polls — the expensive O(N×U) walk happens once/sec per open GUI,
 *  not on every ContainerData.get() call. */
public class BedContainerData implements ContainerData {

    /** How often to recompute the snapshot. 20 ticks = 1 second. Menu broadcasts changes ~once
     *  per tick, so without caching the cluster walk would happen ~20× per second per open GUI. */
    private static final int SAMPLE_INTERVAL_TICKS = 20;

    private final HydroponicsBedBlockEntity be;
    private final int[] snapshot = new int[HydroponicsBedMenu.DATA_COUNT];
    private long lastSampleTick = Long.MIN_VALUE;

    public BedContainerData(HydroponicsBedBlockEntity be) {
        this.be = be;
    }

    private void refreshIfStale() {
        if (be == null) return;
        long now = (be.getLevel() instanceof ServerLevel sl) ? sl.getGameTime() : -1;
        if (now < 0) return;
        if (lastSampleTick != Long.MIN_VALUE && (now - lastSampleTick) < SAMPLE_INTERVAL_TICKS) return;
        lastSampleTick = now;

        snapshot[HydroponicsBedMenu.DATA_WATER_MB]  = be.clusterWaterMb();
        snapshot[HydroponicsBedMenu.DATA_WATER_CAP] = be.clusterWaterCapMb();
        snapshot[HydroponicsBedMenu.DATA_XP_MB]     = be.clusterXpMb();
        snapshot[HydroponicsBedMenu.DATA_XP_CAP]    = be.clusterXpCapMb();
        snapshot[HydroponicsBedMenu.DATA_CLUSTER_SIZE] = be.clusterMembers().size();

        int[] counts = be.clusterPlanterCounts();
        snapshot[HydroponicsBedMenu.DATA_PLANTERS_INSTALLED]  = counts[0];
        snapshot[HydroponicsBedMenu.DATA_PLANTERS_CONFIGURED] = counts[1];
        snapshot[HydroponicsBedMenu.DATA_PLANTERS_GROWING]    = counts[2];
        snapshot[HydroponicsBedMenu.DATA_PLANTERS_READY]      = counts[3];

        snapshot[HydroponicsBedMenu.DATA_WATER_RATE] = be.clusterWaterRateMbPerMin();
        snapshot[HydroponicsBedMenu.DATA_XP_RATE]    = be.clusterXpRateMbPerMin();
        snapshot[HydroponicsBedMenu.DATA_XP_ENABLED] = be.outputFluidEnabled() ? 1 : 0;

        Map<Item, Integer> harvests = be.clusterHarvestRate();
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(harvests.entrySet());
        sorted.sort(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int topN = Math.min(HydroponicsBedMenu.CROP_SLOT_COUNT, sorted.size());
        for (int i = 0; i < HydroponicsBedMenu.CROP_SLOT_COUNT; i++) {
            int itemIdx = HydroponicsBedMenu.DATA_CROP_ITEM_BASE + i * 2;
            int rateIdx = itemIdx + 1;
            if (i < topN) {
                snapshot[itemIdx] = BuiltInRegistries.ITEM.getId(sorted.get(i).getKey());
                snapshot[rateIdx] = sorted.get(i).getValue();
            } else {
                snapshot[itemIdx] = 0;
                snapshot[rateIdx] = 0;
            }
        }
        snapshot[HydroponicsBedMenu.DATA_CROP_OVERFLOW] = Math.max(0, sorted.size() - HydroponicsBedMenu.CROP_SLOT_COUNT);
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
    public int getCount() { return HydroponicsBedMenu.DATA_COUNT; }
}
