package com.kestalkayden.hydrofarm.platform;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** NeoForge capability/transfer implementation of {@link FluidApi}. NeoForge's ResourceHandler
 *  already works in millibuckets, so no unit conversion is needed; insert/extract iterate the
 *  handler's slots so multi-slot stores (tanks with several internal cells) are handled. */
public final class NeoForgeFluidApi implements FluidApi {

    @Override
    public Endpoint find(Level level, BlockPos pos, Direction side) {
        ResourceHandler<FluidResource> handler =
            level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        return handler == null ? null : new NeoEndpoint(handler);
    }

    @Override
    public EndpointCache cache(ServerLevel level, BlockPos pos, Direction side) {
        BlockCapabilityCache<ResourceHandler<FluidResource>, Direction> cache =
            BlockCapabilityCache.create(Capabilities.Fluid.BLOCK, level, pos, side);
        return new EndpointCache() {
            private NeoEndpoint last;   // reuse the wrapper while the handler instance is unchanged
            @Override
            public Endpoint get() {
                ResourceHandler<FluidResource> h = cache.getCapability();
                if (h == null) { last = null; return null; }
                NeoEndpoint e = last;
                if (e == null || e.handler() != h) last = e = new NeoEndpoint(h);
                return e;
            }
        };
    }

    @Override
    public Fluid fluidInItem(ItemStack stack) {
        if (stack.isEmpty()) return Fluids.EMPTY;
        ItemAccess access = ItemAccess.forStack(stack);
        ResourceHandler<FluidResource> handler = access.getCapability(Capabilities.Fluid.ITEM);
        if (handler == null) return Fluids.EMPTY;
        for (int i = 0; i < handler.size(); i++) {
            FluidResource r = handler.getResource(i);
            if (!r.isEmpty()) return r.getFluid();
        }
        return Fluids.EMPTY;
    }

    private record NeoEndpoint(ResourceHandler<FluidResource> handler) implements Endpoint {

        @Override
        public List<Fluid> fluids() {
            List<Fluid> out = new ArrayList<>();
            for (int s = 0; s < handler.size(); s++) {
                FluidResource r = handler.getResource(s);
                if (r.isEmpty()) continue;
                Fluid f = r.getFluid();
                if (!out.contains(f)) out.add(f);
            }
            return out;
        }

        @Override
        public int insert(Fluid fluid, int maxMb) {
            FluidResource resource = FluidResource.of(fluid);
            try (Transaction tx = Transaction.openRoot()) {
                int inserted = 0;
                for (int s = 0; s < handler.size() && inserted < maxMb; s++) {
                    inserted += handler.insert(s, resource, maxMb - inserted, tx);
                }
                tx.commit();
                return inserted;
            }
        }

        @Override
        public int extract(Fluid fluid, int maxMb) {
            FluidResource resource = FluidResource.of(fluid);
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = 0;
                for (int s = 0; s < handler.size() && extracted < maxMb; s++) {
                    extracted += handler.extract(s, resource, maxMb - extracted, tx);
                }
                tx.commit();
                return extracted;
            }
        }

        @Override
        public int moveTo(Endpoint dest, Fluid fluid, int maxMb) {
            ResourceHandler<FluidResource> target = ((NeoEndpoint) dest).handler;
            FluidResource resource = FluidResource.of(fluid);
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = 0;
                for (int s = 0; s < handler.size() && extracted < maxMb; s++) {
                    extracted += handler.extract(s, resource, maxMb - extracted, tx);
                }
                if (extracted <= 0) return 0;
                int inserted = 0;
                for (int s = 0; s < target.size() && inserted < extracted; s++) {
                    inserted += target.insert(s, resource, extracted - inserted, tx);
                }
                // All-or-nothing: commit only if the target accepts the whole extracted amount,
                // otherwise roll back and move nothing. Matches the pumps' original semantics
                // (and is leak-free — a partial insert is never committed).
                if (inserted == extracted) {
                    tx.commit();
                    return inserted;
                }
                return 0;
            }
        }
    }
}
