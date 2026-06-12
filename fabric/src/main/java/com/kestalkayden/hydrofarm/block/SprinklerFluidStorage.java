package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.level.material.Fluids;

/** Transaction-aware, insert-only Fabric Transfer adapter for sprinklers — pipe-mod "fill"
 *  pumps push water in; nothing should drain a sprinkler externally. Snapshot/revert guards
 *  against phantom water gain when a transfer is rolled back. */
public class SprinklerFluidStorage extends SnapshotParticipant<Integer> implements SingleSlotStorage<FluidVariant> {
    private final SprinklerBlockEntity be;
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    public SprinklerFluidStorage(SprinklerBlockEntity be) {
        this.be = be;
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
        if (!resource.isOf(Fluids.WATER)) return 0;
        long maxMb = maxAmount / DROPLETS_PER_MB;
        if (maxMb <= 0) return 0;
        updateSnapshots(tx);
        int acceptedMb = be.insertWater((int) Math.min(maxMb, Integer.MAX_VALUE));
        return acceptedMb * DROPLETS_PER_MB;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
        return 0;
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
