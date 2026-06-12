package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import team.reborn.energy.api.EnergyStorage;

/** Team Reborn Energy adapter for the Autocrafter — insert-only, so tech cables (and Hydrofarm's
 *  own network pull) can fill its buffer while nothing can drain it back out. Transaction-aware via
 *  {@link SnapshotParticipant} so a rolled-back insertion restores the buffer. */
public class AutocrafterEnergyStorage extends SnapshotParticipant<Integer>
        implements EnergyStorage {

    private final AutocrafterBlockEntity be;

    public AutocrafterEnergyStorage(AutocrafterBlockEntity be) {
        this.be = be;
    }

    @Override
    public boolean supportsInsertion() {
        return true;
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        if (maxAmount <= 0) return 0;
        updateSnapshots(transaction);
        return be.insertEnergy((int) Math.min(maxAmount, Integer.MAX_VALUE));
    }

    @Override
    public boolean supportsExtraction() {
        return false;
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        return 0;
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
