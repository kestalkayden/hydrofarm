package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Transaction-aware Fabric Transfer adapter for a Liquid Tank. Cluster-aware: when the tank is
 *  part of a valid rectangular-prism cluster, insert/extract/amount/capacity report and
 *  operate on the whole cluster, with each member's snapshot enrolled in the same transaction
 *  so a rollback restores every modified tank. Fills bottom-up, drains top-down so per-tank
 *  state always matches the visual "water settled at the bottom" model. */
public class LiquidTankFluidStorage extends SnapshotParticipant<LiquidTankBlockEntity.Snapshot>
        implements SingleSlotStorage<FluidVariant> {
    private final LiquidTankBlockEntity be;
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    public LiquidTankFluidStorage(LiquidTankBlockEntity be) {
        this.be = be;
    }

    /** Public alias for {@link #updateSnapshots(TransactionContext)} so a sibling tank's storage
     *  in the same cluster can enroll us into its transaction before mutating our BE directly. */
    public void enrollInTransaction(TransactionContext tx) {
        updateSnapshots(tx);
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext tx) {
        if (resource.isBlank()) return 0;
        long maxMb = maxAmount / DROPLETS_PER_MB;
        if (maxMb <= 0) return 0;

        // Claim-on-fill: a cluster holds exactly ONE fluid. If any member already holds a different
        // fluid, refuse the whole insert — otherwise an EMPTY member would silently accept the stray
        // fluid and the cluster would then sum/relabel across fluids (the water-as-XP duplication bug).
        Fluid current = clusterFluid();
        if (current != Fluids.EMPTY && current != resource.getFluid()) return 0;

        List<LiquidTankBlockEntity> members = clusterMembers();
        long remaining = maxMb;
        int totalAccepted = 0;
        for (LiquidTankBlockEntity m : members) {
            if (remaining <= 0) break;
            m.fluidExposure(LiquidTankFluidStorage::new).enrollInTransaction(tx);
            int accepted = m.insert(resource.getFluid(), (int) Math.min(remaining, Integer.MAX_VALUE));
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted * DROPLETS_PER_MB;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext tx) {
        if (resource.isBlank()) return 0;
        long maxMb = maxAmount / DROPLETS_PER_MB;
        if (maxMb <= 0) return 0;

        List<LiquidTankBlockEntity> members = clusterMembers();
        // Drain top-down so the bottom stays full longest — visual settle stays consistent.
        long remaining = maxMb;
        int totalTaken = 0;
        for (int i = members.size() - 1; i >= 0; i--) {
            if (remaining <= 0) break;
            LiquidTankBlockEntity m = members.get(i);
            m.fluidExposure(LiquidTankFluidStorage::new).enrollInTransaction(tx);
            int taken = m.extract(resource.getFluid(), (int) Math.min(remaining, Integer.MAX_VALUE));
            totalTaken += taken;
            remaining -= taken;
        }
        return totalTaken * DROPLETS_PER_MB;
    }

    @Override
    protected LiquidTankBlockEntity.Snapshot createSnapshot() {
        return be.snapshot();
    }

    @Override
    protected void readSnapshot(LiquidTankBlockEntity.Snapshot snapshot) {
        be.restore(snapshot);
    }

    @Override
    public boolean isResourceBlank() {
        return getResource().isBlank();
    }

    @Override
    public FluidVariant getResource() {
        Fluid f = clusterFluid();
        return f == Fluids.EMPTY ? FluidVariant.blank() : FluidVariant.of(f);
    }

    @Override
    public long getAmount() {
        // Sum ONLY members holding the reported (cluster) fluid — never add a different fluid's mB
        // into this fluid's amount (that mislabelled water as Liquid XP gains).
        Fluid f = clusterFluid();
        if (f == Fluids.EMPTY) return 0;
        long total = 0;
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (m.getFluid() == f) total += m.getAmountMb();
        }
        return total * DROPLETS_PER_MB;
    }

    @Override
    public long getCapacity() {
        return (long) clusterMembers().size() * be.getCapacityMb() * DROPLETS_PER_MB;
    }

    /** The tank cluster's members, resolved + cached per tick on the BE (shared across probes). */
    private List<LiquidTankBlockEntity> clusterMembers() {
        return be.clusterMembers();
    }

    /** Returns the fluid stored by any member of the cluster, or {@link Fluids#EMPTY} if every
     *  member is empty. Members of a properly-managed cluster share a fluid; a mixed-fluid state
     *  is possible if individual tanks were filled before they joined the cluster — in that case
     *  this returns the first non-empty fluid found. */
    private Fluid clusterFluid() {
        for (LiquidTankBlockEntity m : clusterMembers()) {
            if (m.getFluid() != Fluids.EMPTY) return m.getFluid();
        }
        return Fluids.EMPTY;
    }
}
