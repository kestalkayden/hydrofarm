package com.kestalkayden.hydrofarm.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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

    private List<EnergyCellBlockEntity> clusterMembers() {
        Level level = be.getLevel();
        EnergyCellBlockEntity.Cluster cluster = level == null ? null : be.findCluster();
        if (cluster == null || !cluster.isMultiBlock()) return List.of(be);

        ArrayList<EnergyCellBlockEntity> list = new ArrayList<>(cluster.size());
        for (BlockPos pos : cluster.members()) {
            if (level.getBlockEntity(pos) instanceof EnergyCellBlockEntity neighbor) list.add(neighbor);
        }
        return list.isEmpty() ? List.of(be) : list;
    }
}
