package com.kestalkayden.hydrofarm.block;

import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** NeoForge energy adapter for the Autocrafter — insert-only (FE), so tech cables (and Hydrofarm's
 *  own network pull) can fill its buffer while nothing can drain it back out. Uses the 26.1
 *  transfer-API {@link EnergyHandler}; transaction-aware so a rolled-back insertion restores the
 *  buffer. */
public class AutocrafterEnergyHandler extends SnapshotJournal<Integer>
        implements EnergyHandler {

    private final AutocrafterBlockEntity be;

    public AutocrafterEnergyHandler(AutocrafterBlockEntity be) {
        this.be = be;
    }

    @Override
    public long getAmountAsLong() {
        return be.getEnergyStored();
    }

    @Override
    public long getCapacityAsLong() {
        return be.getEnergyCapacity();
    }

    @Override
    public int insert(int amount, TransactionContext tx) {
        if (amount <= 0) return 0;
        updateSnapshots(tx);
        return be.insertEnergy(amount);
    }

    @Override
    public int extract(int amount, TransactionContext tx) {
        return 0;
    }

    @Override
    protected Integer createSnapshot() {
        return be.getEnergyStored();
    }

    @Override
    protected void revertToSnapshot(Integer snapshot) {
        be.setEnergyStored(snapshot);
    }
}
