package com.kestalkayden.hydrofarm.block;

import java.util.List;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Cluster-wrapped item handler for any cluster-bed BE. Routes each slot to the owning member's
 *  live container wrapper, so external hoppers/pipes attached anywhere on the cluster see the
 *  entire pool. Each member's wrapper manages its own snapshot/journal, so transactions still roll
 *  back correctly when an op touches multiple beds. */
public class ClusterBedItemHandler implements ResourceHandler<ItemResource> {
    private final AbstractClusterBedBlockEntity be;

    public ClusterBedItemHandler(AbstractClusterBedBlockEntity be) {
        this.be = be;
    }

    private record Resolved(AbstractClusterBedBlockEntity member, int localSlot) {}

    private Resolved resolve(int slot) {
        List<AbstractClusterBedBlockEntity> members = be.clusterMembers();
        int memberIdx = slot / AbstractClusterBedBlockEntity.SLOT_COUNT;
        int local = slot % AbstractClusterBedBlockEntity.SLOT_COUNT;
        if (memberIdx < 0 || memberIdx >= members.size()) return null;
        return new Resolved(members.get(memberIdx), local);
    }

    /** Live per-bed item handler for {@code m}.
     *
     *  <p>MUST stay a container <em>wrapper</em>. This was previously
     *  {@code new ItemStacksResourceHandler(be.getItems())}, which looks like a wrapper but is not:
     *  {@code StacksResourceHandler} calls {@code mutableCopyOf(stacks)} and operates on a detached
     *  {@code NonNullList} copy. Combined with the old per-BE memoisation, that froze a snapshot at
     *  the first capability query and never reconnected it in either direction — automation could
     *  neither see the bed's real contents nor write to them, and items pushed in were destroyed on
     *  commit. {@code VanillaContainerWrapper} delegates every read/write to
     *  {@code Container.getItem}/{@code setItem} and schedules {@code setChanged()} on root commit,
     *  so writes go through {@link AbstractClusterBedBlockEntity#setItem}, which bumps the item
     *  revision. {@code of()} dedupes per Container identity in a global weak map, so cross-member
     *  transactions still enroll one handler per bed without any caching of our own. */
    private static ResourceHandler<ItemResource> itemHandlerOf(AbstractClusterBedBlockEntity m) {
        return VanillaContainerWrapper.of(m);
    }

    @Override
    public int size() {
        return be.clusterMembers().size() * AbstractClusterBedBlockEntity.SLOT_COUNT;
    }

    @Override
    public ItemResource getResource(int slot) {
        Resolved r = resolve(slot);
        return r == null ? ItemResource.EMPTY : itemHandlerOf(r.member).getResource(r.localSlot);
    }

    @Override
    public long getAmountAsLong(int slot) {
        Resolved r = resolve(slot);
        return r == null ? 0 : itemHandlerOf(r.member).getAmountAsLong(r.localSlot);
    }

    @Override
    public long getCapacityAsLong(int slot, ItemResource resource) {
        Resolved r = resolve(slot);
        return r == null ? 0 : itemHandlerOf(r.member).getCapacityAsLong(r.localSlot, resource);
    }

    @Override
    public boolean isValid(int slot, ItemResource resource) {
        Resolved r = resolve(slot);
        return r != null && itemHandlerOf(r.member).isValid(r.localSlot, resource);
    }

    @Override
    public int insert(int slot, ItemResource resource, int amount, TransactionContext tx) {
        Resolved r = resolve(slot);
        if (r == null) return 0;
        // Animal beds cap each feed type so the buffer doesn't hoard thousands.
        if (!r.member.canAccept(resource.toStack().getItem())) return 0;
        return itemHandlerOf(r.member).insert(r.localSlot, resource, amount, tx);
    }

    @Override
    public int extract(int slot, ItemResource resource, int amount, TransactionContext tx) {
        Resolved r = resolve(slot);
        if (r == null) return 0;
        // Animal beds lock their feed in — produce extracts, food doesn't.
        if (!r.member.canPumpOut(resource.toStack().getItem())) return 0;
        return itemHandlerOf(r.member).extract(r.localSlot, resource, amount, tx);
    }
}
