package com.kestalkayden.hydrofarm.block;

import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** NeoForge energy adapter for the Hydroelectric Generator — extract-only (FE), so tech cables can
 *  pull the buffered energy while nothing can push energy back in. Uses the 26.1 transfer-API
 *  {@link EnergyHandler}; transaction-aware so a rolled-back extraction restores the buffer. */
public class HydroelectricGeneratorEnergyHandler extends SnapshotJournal<Integer>
        implements EnergyHandler {

    private final HydroelectricGeneratorBlockEntity be;

    public HydroelectricGeneratorEnergyHandler(HydroelectricGeneratorBlockEntity be) {
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
        return 0;
    }

    @Override
    public int extract(int amount, TransactionContext tx) {
        if (amount <= 0) return 0;
        updateSnapshots(tx);
        return be.extractEnergy(amount);
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
