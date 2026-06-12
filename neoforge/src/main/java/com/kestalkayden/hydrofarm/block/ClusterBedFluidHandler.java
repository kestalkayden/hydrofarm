package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Two-slot cluster-aware fluid handler for any cluster-bed BE.
 *  Slot 0 = water (insert-only externally).
 *  Slot 1 = Liquid XP (extract-only externally). */
public class ClusterBedFluidHandler extends SnapshotJournal<ClusterBedFluidHandler.Snap>
        implements ResourceHandler<FluidResource> {

    public record Snap(int waterMb, int xpMb) {}

    public static final int SLOT_WATER = 0;
    public static final int SLOT_XP = 1;

    private final AbstractClusterBedBlockEntity be;

    public ClusterBedFluidHandler(AbstractClusterBedBlockEntity be) {
        this.be = be;
    }

    public void enrollInTransaction(TransactionContext tx) { updateSnapshots(tx); }

    @Override
    public int size() { return 2; }

    @Override
    public FluidResource getResource(int slot) {
        if (slot == SLOT_WATER) {
            return clusterWater() > 0 ? FluidResource.of(Fluids.WATER) : FluidResource.EMPTY;
        }
        // Config-disabled output (e.g. butcher XP off): report the slot empty so Jade's fluid
        // display skips it and pipes see nothing to pull. Stored legacy fluid stays intact.
        if (!be.outputFluidEnabled()) return FluidResource.EMPTY;
        return clusterXp() > 0 ? FluidResource.of(be.getOutputFluid()) : FluidResource.EMPTY;
    }

    @Override
    public long getAmountAsLong(int slot) {
        if (slot == SLOT_WATER) return clusterWater();
        return be.outputFluidEnabled() ? clusterXp() : 0;
    }

    @Override
    public long getCapacityAsLong(int slot, FluidResource resource) {
        int members = be.clusterMembers().size();
        if (slot == SLOT_WATER) return (long) members * be.getCapacityMb();
        return be.outputFluidEnabled() ? (long) members * be.getXpCapacityMb() : 0;
    }

    @Override
    public boolean isValid(int slot, FluidResource resource) {
        if (resource.isEmpty()) return false;
        if (slot == SLOT_WATER) return resource.getFluid() == Fluids.WATER;
        return be.outputFluidEnabled() && resource.getFluid() == be.getOutputFluid();
    }

    @Override
    public int insert(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (slot != SLOT_WATER || !isValid(slot, resource) || amount <= 0) return 0;
        List<AbstractClusterBedBlockEntity> members = be.clusterMembers();
        int remaining = amount;
        int totalAccepted = 0;
        for (AbstractClusterBedBlockEntity m : members) {
            if (remaining <= 0) break;
            m.fluidExposure(ClusterBedFluidHandler::new).enrollInTransaction(tx);
            int accepted = m.insertWater(remaining);
            totalAccepted += accepted;
            remaining -= accepted;
        }
        return totalAccepted;
    }

    @Override
    public int extract(int slot, FluidResource resource, int amount, TransactionContext tx) {
        if (slot != SLOT_XP || !isValid(slot, resource) || amount <= 0) return 0;
        // (isValid already rejects a config-disabled output slot.)
        List<AbstractClusterBedBlockEntity> members = be.clusterMembers();
        int remaining = amount;
        int totalTaken = 0;
        for (AbstractClusterBedBlockEntity m : members) {
            if (remaining <= 0) break;
            m.fluidExposure(ClusterBedFluidHandler::new).enrollInTransaction(tx);
            int taken = m.extractLiquidXp(remaining);
            totalTaken += taken;
            remaining -= taken;
        }
        return totalTaken;
    }

    @Override
    protected Snap createSnapshot() { return new Snap(be.getWaterMb(), be.getLiquidXpMb()); }

    @Override
    protected void revertToSnapshot(Snap s) {
        be.setWaterMb(s.waterMb);
        be.setLiquidXpMb(s.xpMb);
    }

    private int clusterWater() {
        int total = 0;
        for (AbstractClusterBedBlockEntity m : be.clusterMembers()) total += m.getWaterMb();
        return total;
    }

    private int clusterXp() {
        int total = 0;
        for (AbstractClusterBedBlockEntity m : be.clusterMembers()) total += m.getLiquidXpMb();
        return total;
    }
}
