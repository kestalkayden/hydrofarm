package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.level.material.Fluids;

/** Fabric Transfer adapter for the Hydroelectric Generator's water input — insert-only and
 *  water-only (water is fuel; it can't be pumped back out). Transaction-aware so a rolled-back
 *  insert restores the tank. */
public class HydroelectricGeneratorWaterStorage extends SnapshotParticipant<Integer>
        implements SingleSlotStorage<FluidVariant> {

    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    private final HydroelectricGeneratorBlockEntity be;

    public HydroelectricGeneratorWaterStorage(HydroelectricGeneratorBlockEntity be) {
        this.be = be;
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
        if (resource.isBlank() || resource.getFluid() != Fluids.WATER) return 0;
        long maxMb = maxAmount / DROPLETS_PER_MB;
        if (maxMb <= 0) return 0;
        updateSnapshots(tx);
        int accepted = be.insertWater((int) Math.min(maxMb, Integer.MAX_VALUE));
        return accepted * DROPLETS_PER_MB;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
        return 0;
    }

    @Override
    public boolean isResourceBlank() {
        return be.getWaterMb() <= 0;
    }

    @Override
    public FluidVariant getResource() {
        return be.getWaterMb() > 0 ? FluidVariant.of(Fluids.WATER) : FluidVariant.blank();
    }

    @Override
    public long getAmount() {
        return (long) be.getWaterMb() * DROPLETS_PER_MB;
    }

    @Override
    public long getCapacity() {
        return (long) be.getWaterCapMb() * DROPLETS_PER_MB;
    }

    @Override
    protected Integer createSnapshot() {
        return be.getWaterMb();
    }

    @Override
    protected void readSnapshot(Integer snapshot) {
        be.setWaterMb(snapshot);
    }
}
