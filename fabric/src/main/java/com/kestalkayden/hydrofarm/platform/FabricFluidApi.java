package com.kestalkayden.hydrofarm.platform;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Fabric Transfer API implementation of {@link FluidApi}. The Transfer API counts in droplets
 *  (81000 per bucket), so amounts are converted to/from millibuckets at the boundary, and every
 *  operation runs inside its own outer transaction. */
public final class FabricFluidApi implements FluidApi {

    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    @Override
    public Endpoint find(Level level, BlockPos pos, Direction side) {
        Storage<FluidVariant> storage = FluidStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new FabricEndpoint(storage);
    }

    @Override
    public EndpointCache cache(ServerLevel level, BlockPos pos, Direction side) {
        BlockApiCache<Storage<FluidVariant>, Direction> cache =
            BlockApiCache.create(FluidStorage.SIDED, level, pos);
        return new EndpointCache() {
            private FabricEndpoint last;   // reuse the wrapper while the storage instance is unchanged
            @Override
            public Endpoint get() {
                Storage<FluidVariant> s = cache.find(side);
                if (s == null) { last = null; return null; }
                FabricEndpoint e = last;
                if (e == null || e.storage() != s) last = e = new FabricEndpoint(s);
                return e;
            }
        };
    }

    @Override
    public Fluid fluidInItem(ItemStack stack) {
        if (stack.isEmpty()) return Fluids.EMPTY;
        Storage<FluidVariant> storage =
            FluidStorage.ITEM.find(stack, ContainerItemContext.withConstant(stack));
        if (storage == null) return Fluids.EMPTY;
        for (StorageView<FluidVariant> view : storage) {
            if (!view.isResourceBlank()) return view.getResource().getFluid();
        }
        return Fluids.EMPTY;
    }

    private record FabricEndpoint(Storage<FluidVariant> storage) implements Endpoint {

        @Override
        public List<Fluid> fluids() {
            List<Fluid> out = new ArrayList<>();
            for (StorageView<FluidVariant> view : storage) {
                if (view.isResourceBlank()) continue;
                Fluid f = view.getResource().getFluid();
                if (!out.contains(f)) out.add(f);
            }
            return out;
        }

        @Override
        public int insert(Fluid fluid, int maxMb) {
            try (Transaction tx = Transaction.openOuter()) {
                long inserted = storage.insert(FluidVariant.of(fluid), (long) maxMb * DROPLETS_PER_MB, tx);
                tx.commit();
                return (int) (inserted / DROPLETS_PER_MB);
            }
        }

        @Override
        public int extract(Fluid fluid, int maxMb) {
            try (Transaction tx = Transaction.openOuter()) {
                long extracted = storage.extract(FluidVariant.of(fluid), (long) maxMb * DROPLETS_PER_MB, tx);
                tx.commit();
                return (int) (extracted / DROPLETS_PER_MB);
            }
        }

        @Override
        public int moveTo(Endpoint dest, Fluid fluid, int maxMb) {
            Storage<FluidVariant> target = ((FabricEndpoint) dest).storage;
            FluidVariant variant = FluidVariant.of(fluid);
            try (Transaction tx = Transaction.openOuter()) {
                long extracted = storage.extract(variant, (long) maxMb * DROPLETS_PER_MB, tx);
                if (extracted <= 0) return 0;
                long inserted = target.insert(variant, extracted, tx);
                // All-or-nothing: commit only if the target accepts the whole extracted amount,
                // otherwise roll back and move nothing. Matches the pumps' original semantics
                // (and is leak-free — a partial insert is never committed).
                if (inserted == extracted) {
                    tx.commit();
                    return (int) (inserted / DROPLETS_PER_MB);
                }
                return 0;
            }
        }
    }
}
