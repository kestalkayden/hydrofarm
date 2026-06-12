package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.level.material.Fluids;

/** Transaction-aware, extract-only Fabric Transfer adapter for Liquid Siphons. Drain pumps can
 *  pull from the siphon's buffer; insertion is denied (the siphon's job is to GENERATE water,
 *  not accept water from elsewhere). Snapshot/revert guards against phantom water loss when a
 *  downstream insert is rolled back. */
public class LiquidSiphonFluidStorage extends SnapshotParticipant<Integer> implements SingleSlotStorage<FluidVariant> {
    private final LiquidSiphonBlockEntity be;
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    public LiquidSiphonFluidStorage(LiquidSiphonBlockEntity be) {
        this.be = be;
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
        return 0;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
        if (!resource.isOf(Fluids.WATER)) return 0;
        long maxMb = maxAmount / DROPLETS_PER_MB;
        if (maxMb <= 0) return 0;
        updateSnapshots(tx);
        int takenMb = be.extractWater((int) Math.min(maxMb, Integer.MAX_VALUE));
        return takenMb * DROPLETS_PER_MB;
    }

    @Override
    protected Integer createSnapshot() {
        return be.getWaterMb();
    }

    @Override
    protected void readSnapshot(Integer snapshot) {
        be.setWaterMb(snapshot);
    }

    @Override
    public boolean isResourceBlank() {
        return be.getWaterMb() == 0;
    }

    @Override
    public FluidVariant getResource() {
        return be.getWaterMb() > 0 ? FluidVariant.of(Fluids.WATER) : FluidVariant.blank();
    }

    @Override
    public long getAmount() {
        return be.getWaterMb() * DROPLETS_PER_MB;
    }

    @Override
    public long getCapacity() {
        return be.getCapacityMb() * (long) DROPLETS_PER_MB;
    }
}
