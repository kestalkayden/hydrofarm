package com.kestalkayden.hydrofarm.block;

import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** NeoForge fluid handler for the Hydroelectric Generator's water input — insert-only and
 *  water-only (water is fuel). Transaction-aware so a rolled-back insert restores the tank. */
public class HydroelectricGeneratorWaterHandler extends SnapshotJournal<Integer>
        implements ResourceHandler<FluidResource> {

    private final HydroelectricGeneratorBlockEntity be;

    public HydroelectricGeneratorWaterHandler(HydroelectricGeneratorBlockEntity be) {
        this.be = be;
    }

    @Override
    public int size() {
        return 1;
    }

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
        return be.getWaterCapMb();
    }

    @Override
    public boolean isValid(int slot, FluidResource resource) {
        return !resource.isEmpty() && resource.getFluid() == Fluids.WATER;
    }

    @Override
    public int insert(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || resource.getFluid() != Fluids.WATER || amount <= 0) return 0;
        updateSnapshots(tx);
        return be.insertWater(amount);
    }

    @Override
    public int extract(int slot, FluidResource resource, int amount, TransactionContext tx) {
        return 0;
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
