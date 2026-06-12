package com.kestalkayden.hydrofarm.platform;

import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** NeoForge implementation of {@link EnergyApi} via the 26.1 transfer-API {@link EnergyHandler}. */
public final class NeoForgeEnergyApi implements EnergyApi {

    @Override
    public Endpoint find(Level level, BlockPos pos, Direction side) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        return handler == null ? null : new NeoEndpoint(handler);
    }

    @Override
    public EndpointCache cache(ServerLevel level, BlockPos pos, Direction side) {
        BlockCapabilityCache<EnergyHandler, Direction> cache =
            BlockCapabilityCache.create(Capabilities.Energy.BLOCK, level, pos, side);
        return new EndpointCache() {
            private NeoEndpoint last;   // reuse the wrapper while the handler instance is unchanged
            @Override
            public Endpoint get() {
                EnergyHandler h = cache.getCapability();
                if (h == null) { last = null; return null; }
                NeoEndpoint e = last;
                if (e == null || e.handler() != h) last = e = new NeoEndpoint(h);
                return e;
            }
        };
    }

    private record NeoEndpoint(EnergyHandler handler) implements Endpoint {

        @Override
        public int extractableEnergy() {
            // Simulate: extract the max inside a transaction we never commit, so it rolls back.
            try (Transaction tx = Transaction.openRoot()) {
                return handler.extract(Integer.MAX_VALUE, tx);
            }
        }

        @Override
        public int moveTo(Endpoint dest, int maxEnergy) {
            if (maxEnergy <= 0) return 0;
            EnergyHandler target = ((NeoEndpoint) dest).handler;
            try (Transaction tx = Transaction.openRoot()) {
                int moved = EnergyHandlerUtil.move(handler, target, maxEnergy, tx);
                tx.commit();
                return moved;
            }
        }
    }
}
