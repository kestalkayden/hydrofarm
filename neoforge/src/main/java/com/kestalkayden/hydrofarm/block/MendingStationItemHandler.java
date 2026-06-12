package com.kestalkayden.hydrofarm.block;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** NeoForge item adapter for the Mending Station: insert damaged gear into the repair slot, extract
 *  the finished item from the repaired-out slot. Delegates storage/transactions to an
 *  {@link ItemStacksResourceHandler} over the BE's 2-slot list, applying the per-slot rules. */
public class MendingStationItemHandler implements ResourceHandler<ItemResource> {

    private static final int IN  = MendingStationBlockEntity.SLOT_REPAIR_IN;
    private static final int OUT = MendingStationBlockEntity.SLOT_REPAIR_OUT;

    private final ItemStacksResourceHandler items;

    public MendingStationItemHandler(MendingStationBlockEntity be) {
        this.items = new ItemStacksResourceHandler(be.items.getItems());
    }

    @Override public int size() { return MendingStationBlockEntity.SLOT_COUNT; }

    @Override public ItemResource getResource(int slot) { return items.getResource(slot); }

    @Override public long getAmountAsLong(int slot) { return items.getAmountAsLong(slot); }

    @Override public long getCapacityAsLong(int slot, ItemResource resource) {
        return items.getCapacityAsLong(slot, resource);
    }

    @Override
    public boolean isValid(int slot, ItemResource resource) {
        if (slot != IN) return false;
        ItemStack s = resource.toStack();
        return s.isDamageableItem() && s.isDamaged();
    }

    @Override
    public int insert(int slot, ItemResource resource, int amount, TransactionContext tx) {
        if (slot != IN) return 0;
        ItemStack s = resource.toStack();
        if (!s.isDamageableItem() || !s.isDamaged()) return 0;
        return items.insert(slot, resource, amount, tx);
    }

    @Override
    public int extract(int slot, ItemResource resource, int amount, TransactionContext tx) {
        if (slot != OUT) return 0;   // only the repaired-out slot is extractable
        return items.extract(slot, resource, amount, tx);
    }
}
