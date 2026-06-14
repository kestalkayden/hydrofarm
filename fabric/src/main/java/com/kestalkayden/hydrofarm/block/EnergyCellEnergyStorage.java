package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import team.reborn.energy.api.EnergyStorage;

/** Transaction-aware Team Reborn {@link EnergyStorage} adapter for the Energy Cell cluster.
 *  Cluster-aware: insert and extract fan out across all connected cells, each enrolled in the
 *  same transaction so a rollback restores every modified cell.
 *
 *  <p>Both {@link #supportsInsertion()} and {@link #supportsExtraction()} return true — this is a
 *  passive bank: generators push in and consumers pull out on the same capability. */
public class EnergyCellEnergyStorage extends SnapshotParticipant<EnergyCellBlockEntity.Snapshot>
        implements EnergyStorage {

    private final EnergyCellBlockEntity be;

    public EnergyCellEnergyStorage(EnergyCellBlockEntity be) {
        this.be = be;
    }

    /** Public alias so a sibling cell's storage can enroll us into an ongoing transaction before
     *  mutating our BE directly — mirrors {@link LiquidTankFluidStorage#enrollInTransaction}. */
    public void enrollInTransaction(TransactionContext tx) {
        updateSnapshots(tx);
    }

    @Override
    public boolean supportsInsertion() { return true; }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        if (maxAmount <= 0) return 0;
        List<EnergyCellBlockEntity> members = clusterMembers();
        long remaining = maxAmount;
        long totalAccepted = 0;
        for (EnergyCellBlockEntity m : members) {
            if (remaining <= 0) break;
            // Enroll each touched cell into the transaction for atomic rollback.
            m.energyExposure(EnergyCellEnergyStorage::new).enrollInTransaction(transaction);
            int accepted = m.insertEnergy((int) Math.min(remaining, Integer.MAX_VALUE));
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted;
    }

    @Override
    public boolean supportsExtraction() { return true; }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        if (maxAmount <= 0) return 0;
        List<EnergyCellBlockEntity> members = clusterMembers();
        long remaining = maxAmount;
        long totalTaken = 0;
        // Drain in reverse (top members first) — symmetric with LiquidTankFluidStorage's
        // drain-top-down policy for consistent multi-cell behaviour.
        for (int i = members.size() - 1; i >= 0; i--) {
            if (remaining <= 0) break;
            EnergyCellBlockEntity m = members.get(i);
            m.energyExposure(EnergyCellEnergyStorage::new).enrollInTransaction(transaction);
            int taken = m.extractEnergy((int) Math.min(remaining, Integer.MAX_VALUE));
            totalTaken += taken;
            remaining -= taken;
        }
        return totalTaken;
    }

    @Override
    public long getAmount() {
        long total = 0;
        for (EnergyCellBlockEntity m : clusterMembers()) {
            total += m.getEnergyStored();
        }
        return total;
    }

    @Override
    public long getCapacity() {
        return (long) clusterMembers().size() * (long) EnergyCellBlockEntity.CELL_CAPACITY;
    }

    // -----------------------------------------------------------------------------------------
    // SnapshotParticipant — snapshot for this cell only (each cell enrolls its own)
    // -----------------------------------------------------------------------------------------

    @Override
    protected EnergyCellBlockEntity.Snapshot createSnapshot() {
        return be.snapshot();
    }

    @Override
    protected void readSnapshot(EnergyCellBlockEntity.Snapshot snapshot) {
        be.restore(snapshot);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** The cell cluster's members, resolved + cached per tick on the BE (shared across probes). */
    private List<EnergyCellBlockEntity> clusterMembers() {
        return be.clusterMembers();
    }
}
