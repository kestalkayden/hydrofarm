package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Cluster-aware NeoForge fluid handler for a Liquid Tank. Same logic as the Fabric storage:
 *  when in a valid rectangular cluster, ops fan out across members with each member's snapshot
 *  enrolled in the same transaction. */
public class LiquidTankFluidHandler extends SnapshotJournal<LiquidTankBlockEntity.Snapshot>
        implements ResourceHandler<FluidResource> {
    private final LiquidTankBlockEntity be;

    public LiquidTankFluidHandler(LiquidTankBlockEntity be) {
        this.be = be;
    }

    public void enrollInTransaction(TransactionContext tx) {
        updateSnapshots(tx);
    }

    @Override
    public int size() { return 1; }

    @Override
    public FluidResource getResource(int slot) {
        Fluid f = clusterFluid();
        return f == Fluids.EMPTY ? FluidResource.EMPTY : FluidResource.of(f);
    }

    @Override
    public long getAmountAsLong(int slot) {
        // Sum ONLY members holding the reported (cluster) fluid — never add a different fluid's mB
        // into this fluid's amount (that mislabelled water as Liquid XP gains).
        Fluid f = clusterFluid();
        if (f == Fluids.EMPTY) return 0;
        long total = 0;
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (m.getFluid() == f) total += m.getAmountMb();
        }
        return total;
    }

    @Override
    public long getCapacityAsLong(int slot, FluidResource resource) {
        return (long) clusterMembers().size() * be.getCapacityMb();
    }

    @Override
    public boolean isValid(int slot, FluidResource resource) {
        if (resource.isEmpty()) return false;
        Fluid incoming = FluidContainers.normalizeXpFluid(resource.getFluid());
        Fluid current = clusterFluid();
        return current == Fluids.EMPTY || current == incoming;
    }

    @Override
    public int insert(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || amount <= 0) return 0;
        // A recognized foreign Liquid XP (same 20 mB/XP ratio) is relabelled to our own so external
        // XP aggregates into our pool; every other fluid passes through untouched. mB is unchanged,
        // so the returned amount is still correct in the source fluid's units.
        Fluid incoming = FluidContainers.normalizeXpFluid(resource.getFluid());
        // Claim-on-fill: a cluster holds exactly ONE fluid. Refuse the insert if any member already
        // holds a different fluid (isValid checks this, but enforce it here so callers that skip
        // isValid can't slip a stray fluid into an empty member and mix the cluster).
        Fluid current = clusterFluid();
        if (current != Fluids.EMPTY && current != incoming) return 0;
        List<LiquidTankBlockEntity> members = clusterMembers();
        int remaining = amount;
        int totalAccepted = 0;
        for (LiquidTankBlockEntity m : members) {
            if (remaining <= 0) break;
            m.fluidExposure(LiquidTankFluidHandler::new).enrollInTransaction(tx);
            int accepted = m.insert(incoming, remaining);
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted;
    }

    @Override
    public int extract(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (resource.isEmpty() || amount <= 0) return 0;
        List<LiquidTankBlockEntity> members = clusterMembers();
        int remaining = amount;
        int totalTaken = 0;
        for (int i = members.size() - 1; i >= 0; i--) {
            if (remaining <= 0) break;
            LiquidTankBlockEntity m = members.get(i);
            m.fluidExposure(LiquidTankFluidHandler::new).enrollInTransaction(tx);
            int taken = m.extract(resource.getFluid(), remaining);
            totalTaken += taken;
            remaining -= taken;
        }
        return totalTaken;
    }

    @Override
    protected LiquidTankBlockEntity.Snapshot createSnapshot() {
        return be.snapshot();
    }

    @Override
    protected void revertToSnapshot(LiquidTankBlockEntity.Snapshot snapshot) {
        be.restore(snapshot);
    }

    /** The tank cluster's members, resolved + cached per tick on the BE (shared across probes). */
    private List<LiquidTankBlockEntity> clusterMembers() {
        return be.clusterMembers();
    }

    private Fluid clusterFluid() {
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (m.getFluid() != Fluids.EMPTY) return m.getFluid();
        }
        return Fluids.EMPTY;
    }
}
