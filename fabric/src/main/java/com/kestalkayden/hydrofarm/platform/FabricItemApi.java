package com.kestalkayden.hydrofarm.platform;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Fabric Transfer API implementation of {@link ItemApi}. */
public final class FabricItemApi implements ItemApi {

    @Override
    public Endpoint find(Level level, BlockPos pos, Direction side) {
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new FabricEndpoint(storage);
    }

    @Override
    public EndpointCache cache(ServerLevel level, BlockPos pos, Direction side) {
        BlockApiCache<Storage<ItemVariant>, Direction> cache =
            BlockApiCache.create(ItemStorage.SIDED, level, pos);
        return new EndpointCache() {
            private FabricEndpoint last;   // reuse the wrapper while the storage instance is unchanged
            @Override
            public Endpoint get() {
                Storage<ItemVariant> s = cache.find(side);
                if (s == null) { last = null; return null; }
                FabricEndpoint e = last;
                if (e == null || e.storage() != s) last = e = new FabricEndpoint(s);
                return e;
            }
        };
    }

    private record FabricEndpoint(Storage<ItemVariant> storage) implements Endpoint {

        @Override
        public List<ItemStack> contents() {
            List<ItemStack> out = new ArrayList<>();
            for (StorageView<ItemVariant> view : storage) {
                if (view.isResourceBlank()) continue;
                ItemStack proto = view.getResource().toStack();
                boolean seen = false;
                for (ItemStack s : out) {
                    if (ItemStack.isSameItemSameComponents(s, proto)) { seen = true; break; }
                }
                if (!seen) out.add(proto);
            }
            return out;
        }

        @Override
        public int moveTo(Endpoint dest, ItemStack prototype, int maxCount) {
            if (maxCount <= 0 || prototype.isEmpty()) return 0;
            Storage<ItemVariant> target = ((FabricEndpoint) dest).storage;
            ItemVariant variant = ItemVariant.of(prototype);
            try (Transaction tx = Transaction.openOuter()) {
                long extracted = storage.extract(variant, maxCount, tx);
                if (extracted == 0) return 0;
                long inserted = target.insert(variant, extracted, tx);
                if (inserted == 0) return 0;
                if (inserted == extracted) {
                    tx.commit();
                    return (int) inserted;
                }
                // Partial: return the over-extraction to the source; commit only if it all goes
                // back, so an item is never destroyed (matches the item pump's original logic).
                long over = extracted - inserted;
                if (storage.insert(variant, over, tx) == over) {
                    tx.commit();
                    return (int) inserted;
                }
                return 0;
            }
        }

        @Override
        public int amountAvailable(ItemStack prototype) {
            if (prototype.isEmpty()) return 0;
            ItemVariant variant = ItemVariant.of(prototype);
            // Simulate: extract the max inside a transaction we never commit, so it rolls back.
            try (Transaction tx = Transaction.openOuter()) {
                return (int) Math.min(storage.extract(variant, Integer.MAX_VALUE, tx), Integer.MAX_VALUE);
            }
        }

        @Override
        public ItemStack extract(ItemStack prototype, int maxCount) {
            if (maxCount <= 0 || prototype.isEmpty()) return ItemStack.EMPTY;
            ItemVariant variant = ItemVariant.of(prototype);
            try (Transaction tx = Transaction.openOuter()) {
                long got = storage.extract(variant, maxCount, tx);
                if (got <= 0) return ItemStack.EMPTY;
                tx.commit();
                return prototype.copyWithCount((int) got);
            }
        }

        @Override
        public int insert(ItemStack stack) {
            if (stack.isEmpty()) return 0;
            ItemVariant variant = ItemVariant.of(stack);
            try (Transaction tx = Transaction.openOuter()) {
                long ins = storage.insert(variant, stack.getCount(), tx);
                if (ins <= 0) return 0;
                tx.commit();
                return (int) ins;
            }
        }
    }
}
