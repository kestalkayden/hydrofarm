package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import team.reborn.energy.api.EnergyStorage;

/** Team Reborn Energy adapter for the Hydroelectric Generator — extract-only, so tech cables can
 *  pull the buffered energy while nothing can push energy back in. Transaction-aware via
 *  {@link SnapshotParticipant} so a rolled-back extraction restores the buffer. */
public class HydroelectricGeneratorEnergyStorage extends SnapshotParticipant<Integer>
        implements EnergyStorage {

    private final HydroelectricGeneratorBlockEntity be;

    public HydroelectricGeneratorEnergyStorage(HydroelectricGeneratorBlockEntity be) {
        this.be = be;
    }

    @Override
    public boolean supportsInsertion() {
        return false;
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        return 0;
    }

    @Override
    public boolean supportsExtraction() {
        return true;
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        if (maxAmount <= 0) return 0;
        updateSnapshots(transaction);
        return be.extractEnergy((int) Math.min(maxAmount, Integer.MAX_VALUE));
    }

    @Override
    public long getAmount() {
        return be.getEnergyStored();
    }

    @Override
    public long getCapacity() {
        return be.getEnergyCapacity();
    }

    @Override
    protected Integer createSnapshot() {
        return be.getEnergyStored();
    }

    @Override
    protected void readSnapshot(Integer snapshot) {
        be.setEnergyStored(snapshot);
    }
}
