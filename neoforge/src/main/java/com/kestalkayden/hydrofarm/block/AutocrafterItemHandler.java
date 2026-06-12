package com.kestalkayden.hydrofarm.block;

import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.ResourceHandler;

/** NeoForge item adapter for the Autocrafter. Slots 0..8 are the template-filtered input (insert
 *  only — only the recipe's ingredients are accepted), slot 9 is the crafted output (extract only).
 *  Delegates storage + transaction handling to two {@link ItemStacksResourceHandler}s over the BE's
 *  input/output lists, applying the per-direction rules on top. */
public class AutocrafterItemHandler implements ResourceHandler<ItemResource> {

    private static final int INPUT_SIZE = AutocrafterBlockEntity.GRID_SIZE;   // 9
    private static final int SIZE = INPUT_SIZE + 1;                            // + output

    private final AutocrafterBlockEntity be;
    private final ItemStacksResourceHandler input;
    private final ItemStacksResourceHandler output;

    public AutocrafterItemHandler(AutocrafterBlockEntity be) {
        this.be = be;
        this.input = new ItemStacksResourceHandler(be.inputContainer.getItems());
        this.output = new ItemStacksResourceHandler(be.outputContainer.getItems());
    }

    @Override public int size() { return SIZE; }

    @Override
    public ItemResource getResource(int slot) {
        return slot < INPUT_SIZE ? input.getResource(slot) : output.getResource(0);
    }

    @Override
    public long getAmountAsLong(int slot) {
        return slot < INPUT_SIZE ? input.getAmountAsLong(slot) : output.getAmountAsLong(0);
    }

    @Override
    public long getCapacityAsLong(int slot, ItemResource resource) {
        return slot < INPUT_SIZE ? input.getCapacityAsLong(slot, resource) : output.getCapacityAsLong(0, resource);
    }

    @Override
    public boolean isValid(int slot, ItemResource resource) {
        return slot < INPUT_SIZE && be.acceptsInput(slot, resource.toStack());
    }

    @Override
    public int insert(int slot, ItemResource resource, int amount, TransactionContext tx) {
        if (slot >= INPUT_SIZE) return 0;                                   // output is extract-only
        if (!be.acceptsInput(slot, resource.toStack())) return 0;          // template filter
        return input.insert(slot, resource, amount, tx);
    }

    @Override
    public int extract(int slot, ItemResource resource, int amount, TransactionContext tx) {
        if (slot < INPUT_SIZE) return 0;                                    // input is insert-only
        return output.extract(0, resource, amount, tx);
    }
}
