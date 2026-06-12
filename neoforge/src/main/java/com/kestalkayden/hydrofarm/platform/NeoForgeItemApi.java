package com.kestalkayden.hydrofarm.platform;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** NeoForge capability/transfer implementation of {@link ItemApi}. */
public final class NeoForgeItemApi implements ItemApi {

    @Override
    public Endpoint find(Level level, BlockPos pos, Direction side) {
        ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        return handler == null ? null : new NeoEndpoint(handler);
    }

    @Override
    public EndpointCache cache(ServerLevel level, BlockPos pos, Direction side) {
        BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> cache =
            BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, pos, side);
        return new EndpointCache() {
            private NeoEndpoint last;   // reuse the wrapper while the handler instance is unchanged
            @Override
            public Endpoint get() {
                ResourceHandler<ItemResource> h = cache.getCapability();
                if (h == null) { last = null; return null; }
                NeoEndpoint e = last;
                if (e == null || e.handler() != h) last = e = new NeoEndpoint(h);
                return e;
            }
        };
    }

    private record NeoEndpoint(ResourceHandler<ItemResource> handler) implements Endpoint {

        @Override
        public List<ItemStack> contents() {
            List<ItemStack> out = new ArrayList<>();
            for (int s = 0; s < handler.size(); s++) {
                ItemResource r = handler.getResource(s);
                if (r.isEmpty()) continue;
                ItemStack proto = r.toStack();
                boolean seen = false;
                for (ItemStack x : out) {
                    if (ItemStack.isSameItemSameComponents(x, proto)) { seen = true; break; }
                }
                if (!seen) out.add(proto);
            }
            return out;
        }

        @Override
        public int moveTo(Endpoint dest, ItemStack prototype, int maxCount) {
            if (maxCount <= 0 || prototype.isEmpty()) return 0;
            ResourceHandler<ItemResource> target = ((NeoEndpoint) dest).handler;
            ItemResource resource = ItemResource.of(prototype);
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = 0;
                for (int s = 0; s < handler.size() && extracted < maxCount; s++) {
                    extracted += handler.extract(s, resource, maxCount - extracted, tx);
                }
                if (extracted == 0) return 0;
                int inserted = 0;
                for (int s = 0; s < target.size() && inserted < extracted; s++) {
                    inserted += target.insert(s, resource, extracted - inserted, tx);
                }
                if (inserted == 0) return 0;
                if (inserted < extracted) {
                    // Return the over-extraction to the source; bail (rollback) if it can't all go
                    // back, so an item is never destroyed (matches the item pump's original logic).
                    int over = extracted - inserted;
                    int returned = 0;
                    for (int s = 0; s < handler.size() && returned < over; s++) {
                        returned += handler.insert(s, resource, over - returned, tx);
                    }
                    if (returned != over) return 0;
                }
                tx.commit();
                return inserted;
            }
        }

        @Override
        public int amountAvailable(ItemStack prototype) {
            if (prototype.isEmpty()) return 0;
            ItemResource resource = ItemResource.of(prototype);
            // Simulate: extract the max inside a transaction we never commit, so it rolls back.
            try (Transaction tx = Transaction.openRoot()) {
                int got = 0;
                for (int s = 0; s < handler.size() && got < Integer.MAX_VALUE; s++) {
                    got += handler.extract(s, resource, Integer.MAX_VALUE - got, tx);
                }
                return got;
            }
        }

        @Override
        public ItemStack extract(ItemStack prototype, int maxCount) {
            if (maxCount <= 0 || prototype.isEmpty()) return ItemStack.EMPTY;
            ItemResource resource = ItemResource.of(prototype);
            try (Transaction tx = Transaction.openRoot()) {
                int got = 0;
                for (int s = 0; s < handler.size() && got < maxCount; s++) {
                    got += handler.extract(s, resource, maxCount - got, tx);
                }
                if (got <= 0) return ItemStack.EMPTY;
                tx.commit();
                return prototype.copyWithCount(got);
            }
        }

        @Override
        public int insert(ItemStack stack) {
            if (stack.isEmpty()) return 0;
            ItemResource resource = ItemResource.of(stack);
            try (Transaction tx = Transaction.openRoot()) {
                int ins = 0;
                for (int s = 0; s < handler.size() && ins < stack.getCount(); s++) {
                    ins += handler.insert(s, resource, stack.getCount() - ins, tx);
                }
                if (ins <= 0) return 0;
                tx.commit();
                return ins;
            }
        }
    }
}
