package com.kestalkayden.hydrofarm.block;

import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import com.kestalkayden.hydrofarm.platform.EnergyBuffer;

/** NeoForge energy adapter for any {@link EnergyBuffer} — insert-only (FE), so cables (and the
 *  block's own network pull) can fill it while nothing drains it back out. Transaction-aware so a
 *  rolled-back insertion restores the buffer. */
public class InsertOnlyEnergyHandler extends SnapshotJournal<Integer> implements EnergyHandler {

    private final EnergyBuffer buffer;

    public InsertOnlyEnergyHandler(EnergyBuffer buffer) {
        this.buffer = buffer;
    }

    @Override public long getAmountAsLong() { return buffer.getEnergyStored(); }

    @Override public long getCapacityAsLong() { return buffer.getEnergyCapacity(); }

    @Override
    public int insert(int amount, TransactionContext tx) {
        if (amount <= 0) return 0;
        updateSnapshots(tx);
        return buffer.insertEnergy(amount);
    }

    @Override public int extract(int amount, TransactionContext tx) { return 0; }

    @Override protected Integer createSnapshot() { return buffer.getEnergyStored(); }

    @Override protected void revertToSnapshot(Integer snapshot) { buffer.setEnergyStored(snapshot); }
}
