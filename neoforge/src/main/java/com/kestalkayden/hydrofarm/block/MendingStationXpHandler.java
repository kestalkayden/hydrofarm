package com.kestalkayden.hydrofarm.block;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import com.kestalkayden.hydrofarm.HydrofarmRefs;

/** NeoForge fluid handler for the Mending Station's Liquid XP input — insert-only and Liquid-XP-only.
 *  Mirrors the generator's water input. */
public class MendingStationXpHandler extends SnapshotJournal<Integer>
        implements ResourceHandler<FluidResource> {

    private final MendingStationBlockEntity be;

    public MendingStationXpHandler(MendingStationBlockEntity be) {
        this.be = be;
    }

    @Override public int size() { return 1; }

    @Override
    public FluidResource getResource(int slot) {
        return be.getXpStored() > 0 ? FluidResource.of(HydrofarmRefs.LIQUID_XP.get()) : FluidResource.EMPTY;
    }

    @Override public long getAmountAsLong(int slot) { return be.getXpStored(); }

    @Override public long getCapacityAsLong(int slot, FluidResource resource) { return be.getXpCapacity(); }

    @Override
    public boolean isValid(int slot, FluidResource resource) {
        return !resource.isEmpty() && resource.getFluid() == HydrofarmRefs.LIQUID_XP.get();
    }

    @Override
    public int insert(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || resource.getFluid() != HydrofarmRefs.LIQUID_XP.get() || amount <= 0) return 0;
        updateSnapshots(tx);
        return be.insertXp(resource.getFluid(), amount);
    }

    @Override
    public int extract(int slot, FluidResource resource, int amount, TransactionContext tx) {
        return 0;
    }

    @Override protected Integer createSnapshot() { return be.getXpStored(); }

    @Override protected void revertToSnapshot(Integer snapshot) { be.setXpStored(snapshot); }
}
