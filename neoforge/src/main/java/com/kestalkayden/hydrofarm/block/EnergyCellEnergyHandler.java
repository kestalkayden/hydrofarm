package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Cluster-aware NeoForge {@link EnergyHandler} for the Energy Cell. Both insert and extract are
 *  supported — this is a passive bank: generators push in and consumers pull out via the same
 *  capability. Each touched cell is enrolled in the transaction so rollbacks restore every member.
 *
 *  <p>Mirrors {@link LiquidTankFluidHandler}'s cluster fan-out pattern, adapted for energy. */
public class EnergyCellEnergyHandler extends SnapshotJournal<EnergyCellBlockEntity.Snapshot>
        implements EnergyHandler {

    private final EnergyCellBlockEntity be;

    public EnergyCellEnergyHandler(EnergyCellBlockEntity be) {
        this.be = be;
    }

    /** Public alias so a sibling cell's handler can enroll us into an ongoing transaction. */
    public void enrollInTransaction(TransactionContext tx) {
        updateSnapshots(tx);
    }

    @Override
    public long getAmountAsLong() {
        long total = 0;
        for (EnergyCellBlockEntity m : clusterMembers()) {
            total += m.getEnergyStored();
        }
        return total;
    }

    @Override
    public long getCapacityAsLong() {
        return (long) clusterMembers().size() * (long) EnergyCellBlockEntity.CELL_CAPACITY;
    }

    @Override
    public int insert(int amount, TransactionContext tx) {
        if (amount <= 0) return 0;
        List<EnergyCellBlockEntity> members = clusterMembers();
        int remaining = amount;
        int totalAccepted = 0;
        for (EnergyCellBlockEntity m : members) {
            if (remaining <= 0) break;
            // Skip full cells BEFORE enrolling. insertEnergy() already returns 0 for them without
            // mutating, so they never needed a snapshot — but enrolling still allocated one and
            // dirtied the cell again on rollback. Consumers probe extractable energy by SIMULATING a
            // full extract, so this path runs constantly with nothing to move.
            if (m.getEnergyStored() >= EnergyCellBlockEntity.CELL_CAPACITY) continue;
            // Enroll each touched cell so rollback restores all modified state.
            m.energyExposure(EnergyCellEnergyHandler::new).enrollInTransaction(tx);
            int accepted = m.insertEnergy(remaining);
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted;
    }

    @Override
    public int extract(int amount, TransactionContext tx) {
        if (amount <= 0) return 0;
        List<EnergyCellBlockEntity> members = clusterMembers();
        int remaining = amount;
        int totalTaken = 0;
        // Drain reverse (top members first) to mirror the Fabric wrapper's policy.
        for (int i = members.size() - 1; i >= 0; i--) {
            if (remaining <= 0) break;
            EnergyCellBlockEntity m = members.get(i);
            // Skip empty cells BEFORE enrolling — see insert(). This is the hot one: redistribute
            // packs energy bottom-up while this drains top-down, so the scan starts at the cells
            // guaranteed to be empty and previously enrolled every one of them.
            if (m.getEnergyStored() == 0) continue;
            m.energyExposure(EnergyCellEnergyHandler::new).enrollInTransaction(tx);
            int taken = m.extractEnergy(remaining);
            totalTaken += taken;
            remaining -= taken;
        }
        return totalTaken;
    }

    // -----------------------------------------------------------------------------------------
    // SnapshotJournal — snapshot covers this cell only (each cell enrolls its own)
    // -----------------------------------------------------------------------------------------

    @Override
    protected EnergyCellBlockEntity.Snapshot createSnapshot() {
        return be.snapshot();
    }

    @Override
    protected void revertToSnapshot(EnergyCellBlockEntity.Snapshot snapshot) {
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
