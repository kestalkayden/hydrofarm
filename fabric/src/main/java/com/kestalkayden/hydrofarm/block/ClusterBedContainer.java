package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Cluster-wrapped Container: aggregates per-bed 9-slot containers into one flat slot space.
 *  Used by Fabric Transfer's ContainerStorage.of(...) so external hoppers attached to any bed
 *  in the cluster see the entire pool. Re-resolves the member list on each operation, so
 *  cluster changes (joining/splitting) are picked up without invalidation.
 *
 *  <p>Implements {@link WorldlyContainer} so sided extraction honours each bed's
 *  {@link AbstractClusterBedBlockEntity#canPumpOut(net.minecraft.world.item.Item)} — animal beds
 *  use it to lock their feed in (food can be pumped/hoppered IN but never OUT). Insertion is
 *  unrestricted; only extraction is gated. Beds with the default rule (hydroponics/tree) are
 *  unaffected. */
public class ClusterBedContainer implements WorldlyContainer {
    private final List<? extends AbstractClusterBedBlockEntity> members;
    private final int totalSize;
    private final int[] slotsForFace;

    public ClusterBedContainer(List<? extends AbstractClusterBedBlockEntity> members) {
        this.members = members;
        this.totalSize = members.size() * AbstractClusterBedBlockEntity.SLOT_COUNT;
        this.slotsForFace = new int[totalSize];
        for (int i = 0; i < totalSize; i++) slotsForFace[i] = i;
    }

    private record Resolved(AbstractClusterBedBlockEntity be, int localSlot) {}

    private Resolved resolve(int slot) {
        if (slot < 0 || slot >= totalSize) return null;
        int memberIdx = slot / AbstractClusterBedBlockEntity.SLOT_COUNT;
        int local = slot % AbstractClusterBedBlockEntity.SLOT_COUNT;
        return new Resolved(members.get(memberIdx), local);
    }

    @Override public int getContainerSize() { return totalSize; }

    @Override
    public boolean isEmpty() {
        for (AbstractClusterBedBlockEntity m : members) if (!m.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        Resolved r = resolve(slot);
        return r == null ? ItemStack.EMPTY : r.be.getItem(r.localSlot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        Resolved r = resolve(slot);
        return r == null ? ItemStack.EMPTY : r.be.removeItem(r.localSlot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        Resolved r = resolve(slot);
        return r == null ? ItemStack.EMPTY : r.be.removeItemNoUpdate(r.localSlot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        Resolved r = resolve(slot);
        if (r != null) r.be.setItem(r.localSlot, stack);
    }

    @Override
    public void setChanged() {
        for (AbstractClusterBedBlockEntity m : members) m.setChanged();
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        for (AbstractClusterBedBlockEntity m : members) m.clearContent();
    }

    // ---- WorldlyContainer — sided rules honoured by Fabric's ContainerStorage ----------------

    @Override
    public int[] getSlotsForFace(Direction side) {
        return slotsForFace;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction dir) {
        Resolved r = resolve(slot);
        return r != null && r.be.canAccept(stack.getItem());  // feed is capped, everything else free
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        Resolved r = resolve(slot);
        return r != null && r.be.canPumpOut(stack.getItem());  // feed is locked in
    }
}
