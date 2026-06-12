package com.kestalkayden.hydrofarm.block;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import team.reborn.energy.api.EnergyStorage;

import com.kestalkayden.hydrofarm.platform.EnergyBuffer;

/** Team Reborn Energy adapter for any {@link EnergyBuffer} — insert-only, so cables (and the block's
 *  own network pull) can fill it while nothing drains it back out. Transaction-aware via
 *  {@link SnapshotParticipant} so a rolled-back insertion restores the buffer. */
public class InsertOnlyEnergyStorage extends SnapshotParticipant<Integer> implements EnergyStorage {

    private final EnergyBuffer buffer;

    public InsertOnlyEnergyStorage(EnergyBuffer buffer) {
        this.buffer = buffer;
    }

    @Override public boolean supportsInsertion() { return true; }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        if (maxAmount <= 0) return 0;
        updateSnapshots(transaction);
        return buffer.insertEnergy((int) Math.min(maxAmount, Integer.MAX_VALUE));
    }

    @Override public boolean supportsExtraction() { return false; }

    @Override public long extract(long maxAmount, TransactionContext transaction) { return 0; }

    @Override public long getAmount() { return buffer.getEnergyStored(); }

    @Override public long getCapacity() { return buffer.getEnergyCapacity(); }

    @Override protected Integer createSnapshot() { return buffer.getEnergyStored(); }

    @Override protected void readSnapshot(Integer snapshot) { buffer.setEnergyStored(snapshot); }
}
