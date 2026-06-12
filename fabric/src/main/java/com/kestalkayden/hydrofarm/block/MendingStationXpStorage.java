package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

/** Fabric Transfer adapter for the Mending Station's Liquid XP input — insert-only and Liquid-XP-only
 *  (XP is fuel; it can't be pumped back out). Mirrors the generator's water input. */
public class MendingStationXpStorage extends SnapshotParticipant<Integer>
        implements SingleSlotStorage<FluidVariant> {

    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    private final MendingStationBlockEntity be;

    public MendingStationXpStorage(MendingStationBlockEntity be) {
        this.be = be;
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
        if (resource.isBlank() || resource.getFluid() != HydrofarmRefs.LIQUID_XP.get()) return 0;
        long maxMb = maxAmount / DROPLETS_PER_MB;
        if (maxMb <= 0) return 0;
        updateSnapshots(tx);
        int accepted = be.insertXp(resource.getFluid(), (int) Math.min(maxMb, Integer.MAX_VALUE));
        return accepted * DROPLETS_PER_MB;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
        return 0;
    }

    @Override
    public boolean isResourceBlank() {
        return be.getXpStored() <= 0;
    }

    @Override
    public FluidVariant getResource() {
        return be.getXpStored() > 0 ? FluidVariant.of(HydrofarmRefs.LIQUID_XP.get()) : FluidVariant.blank();
    }

    @Override
    public long getAmount() {
        return (long) be.getXpStored() * DROPLETS_PER_MB;
    }

    @Override
    public long getCapacity() {
        return (long) be.getXpCapacity() * DROPLETS_PER_MB;
    }

    @Override
    protected Integer createSnapshot() {
        return be.getXpStored();
    }

    @Override
    protected void readSnapshot(Integer snapshot) {
        be.setXpStored(snapshot);
    }
}
