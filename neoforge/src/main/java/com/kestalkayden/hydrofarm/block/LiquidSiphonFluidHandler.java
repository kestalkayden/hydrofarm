package com.kestalkayden.hydrofarm.block;

import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Transaction-aware, extract-only NeoForge fluid handler for Liquid Siphons. Drain pumps can
 *  pull from the buffer; insertion is denied. Snapshot/revert guards against phantom water
 *  loss when a downstream insert is rolled back. */
public class LiquidSiphonFluidHandler extends SnapshotJournal<Integer> implements ResourceHandler<FluidResource> {
    private final LiquidSiphonBlockEntity be;

    public LiquidSiphonFluidHandler(LiquidSiphonBlockEntity be) {
        this.be = be;
    }

    @Override
    public int size() { return 1; }

    @Override
    public FluidResource getResource(int slot) {
        return be.getWaterMb() > 0 ? FluidResource.of(Fluids.WATER) : FluidResource.EMPTY;
    }

    @Override
    public long getAmountAsLong(int slot) {
        return be.getWaterMb();
    }

    @Override
    public long getCapacityAsLong(int slot, FluidResource resource) {
        return be.getCapacityMb();
    }

    @Override
    public boolean isValid(int slot, FluidResource resource) {
        return !resource.isEmpty() && resource.getFluid() == Fluids.WATER;
    }

    @Override
    public int insert(int slot, FluidResource resource, int amount, TransactionContext tx) {
        return 0;
    }

    @Override
    public int extract(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || resource.getFluid() != Fluids.WATER) return 0;
        updateSnapshots(tx);
        return be.extractWater(amount);
    }

    @Override
    protected Integer createSnapshot() {
        return be.getWaterMb();
    }

    @Override
    protected void revertToSnapshot(Integer snapshot) {
        be.setWaterMb(snapshot);
    }
}
