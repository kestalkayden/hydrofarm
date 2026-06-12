package com.kestalkayden.hydrofarm.block;

import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.level.material.Fluids;

/** Two-slot cluster-aware fluid storage for any cluster-bed BE.
 *  Slot 0 = water (insert-only externally, drained internally by subclass tick logic).
 *  Slot 1 = Liquid XP (extract-only externally, produced internally by harvests/culls).
 *  Each slot has its own snapshot participant so transactions only enroll the buffer that
 *  was actually touched, and cluster-wide ops enroll each member's relevant slot. */
public class ClusterBedFluidStorage implements SlottedStorage<FluidVariant> {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    private final AbstractClusterBedBlockEntity be;
    private final WaterSlot waterSlot;
    private final XpSlot xpSlot;

    public ClusterBedFluidStorage(AbstractClusterBedBlockEntity be) {
        this.be = be;
        this.waterSlot = new WaterSlot();
        this.xpSlot = new XpSlot();
    }

    public WaterSlot waterSlot() { return waterSlot; }
    public XpSlot xpSlot() { return xpSlot; }

    @Override
    public int getSlotCount() { return 2; }

    @Override
    public SingleSlotStorage<FluidVariant> getSlot(int slot) {
        return slot == 0 ? waterSlot : xpSlot;
    }

    @Override
    public List<SingleSlotStorage<FluidVariant>> getSlots() {
        return List.of(waterSlot, xpSlot);
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
        return waterSlot.insert(resource, maxAmount, tx);
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
        return xpSlot.extract(resource, maxAmount, tx);
    }

    @Override
    public Iterator<StorageView<FluidVariant>> iterator() {
        return new Iterator<>() {
            int i = 0;
            @Override public boolean hasNext() { return i < 2; }
            @Override public StorageView<FluidVariant> next() { return i++ == 0 ? waterSlot : xpSlot; }
        };
    }

    private List<AbstractClusterBedBlockEntity> clusterMembers() {
        return be.clusterMembers();
    }

    /** Water slot — accepts inserts from external pumps, never extracts. */
    public class WaterSlot extends SnapshotParticipant<Integer> implements SingleSlotStorage<FluidVariant> {
        public void enrollInTransaction(TransactionContext tx) { updateSnapshots(tx); }

        @Override
        protected Integer createSnapshot() { return be.getWaterMb(); }

        @Override
        protected void readSnapshot(Integer snapshot) { be.setWaterMb(snapshot); }

        @Override
        public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
            if (!resource.isOf(Fluids.WATER)) return 0;
            long maxMb = maxAmount / DROPLETS_PER_MB;
            if (maxMb <= 0) return 0;
            long remaining = maxMb;
            int totalAccepted = 0;
            for (AbstractClusterBedBlockEntity m : clusterMembers()) {
                if (remaining <= 0) break;
                m.fluidExposure(ClusterBedFluidStorage::new).waterSlot.enrollInTransaction(tx);
                int accepted = m.insertWater((int) Math.min(remaining, Integer.MAX_VALUE));
                totalAccepted += accepted;
                remaining -= accepted;
            }
            return totalAccepted * DROPLETS_PER_MB;
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) { return 0; }

        @Override
        public boolean isResourceBlank() { return clusterWaterMb() == 0; }

        @Override
        public FluidVariant getResource() {
            return clusterWaterMb() > 0 ? FluidVariant.of(Fluids.WATER) : FluidVariant.blank();
        }

        @Override
        public long getAmount() { return clusterWaterMb() * DROPLETS_PER_MB; }

        @Override
        public long getCapacity() {
            return (long) clusterMembers().size() * be.getCapacityMb() * DROPLETS_PER_MB;
        }

        private int clusterWaterMb() {
            int total = 0;
            for (AbstractClusterBedBlockEntity m : clusterMembers()) total += m.getWaterMb();
            return total;
        }
    }

    /** XP slot — produced internally by subclass tick logic (crop harvest, animal cull),
     *  drained externally by pumps. */
    public class XpSlot extends SnapshotParticipant<Integer> implements SingleSlotStorage<FluidVariant> {
        public void enrollInTransaction(TransactionContext tx) { updateSnapshots(tx); }

        @Override
        protected Integer createSnapshot() { return be.getLiquidXpMb(); }

        @Override
        protected void readSnapshot(Integer snapshot) { be.setLiquidXpMb(snapshot); }

        @Override
        public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) { return 0; }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
            if (!be.outputFluidEnabled() || !resource.isOf(be.getOutputFluid())) return 0;
            long maxMb = maxAmount / DROPLETS_PER_MB;
            if (maxMb <= 0) return 0;
            long remaining = maxMb;
            int totalTaken = 0;
            for (AbstractClusterBedBlockEntity m : clusterMembers()) {
                if (remaining <= 0) break;
                m.fluidExposure(ClusterBedFluidStorage::new).xpSlot.enrollInTransaction(tx);
                int taken = m.extractLiquidXp((int) Math.min(remaining, Integer.MAX_VALUE));
                totalTaken += taken;
                remaining -= taken;
            }
            return totalTaken * DROPLETS_PER_MB;
        }

        // A config-disabled output (e.g. butcher XP off) reports blank/0/0 so Jade's fluid display
        // skips the slot and pipes see nothing to pull. Stored legacy fluid stays intact.
        @Override
        public boolean isResourceBlank() { return !be.outputFluidEnabled() || clusterXpMb() == 0; }

        @Override
        public FluidVariant getResource() {
            return be.outputFluidEnabled() && clusterXpMb() > 0
                ? FluidVariant.of(be.getOutputFluid()) : FluidVariant.blank();
        }

        @Override
        public long getAmount() { return be.outputFluidEnabled() ? clusterXpMb() * DROPLETS_PER_MB : 0; }

        @Override
        public long getCapacity() {
            if (!be.outputFluidEnabled()) return 0;
            return (long) clusterMembers().size() * be.getXpCapacityMb() * DROPLETS_PER_MB;
        }

        private int clusterXpMb() {
            int total = 0;
            for (AbstractClusterBedBlockEntity m : clusterMembers()) total += m.getLiquidXpMb();
            return total;
        }
    }
}
