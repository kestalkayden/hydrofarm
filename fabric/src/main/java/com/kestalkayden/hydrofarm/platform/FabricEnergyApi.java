package com.kestalkayden.hydrofarm.platform;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.EnergyStorageUtil;

/** Team Reborn Energy implementation of {@link EnergyApi} — the Fabric energy standard. */
public final class FabricEnergyApi implements EnergyApi {

    @Override
    public Endpoint find(Level level, BlockPos pos, Direction side) {
        EnergyStorage storage = EnergyStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new FabricEndpoint(storage);
    }

    @Override
    public EndpointCache cache(ServerLevel level, BlockPos pos, Direction side) {
        BlockApiCache<EnergyStorage, Direction> cache =
            BlockApiCache.create(EnergyStorage.SIDED, level, pos);
        return new EndpointCache() {
            private FabricEndpoint last;   // reuse the wrapper while the storage instance is unchanged
            @Override
            public Endpoint get() {
                EnergyStorage s = cache.find(side);
                if (s == null) { last = null; return null; }
                FabricEndpoint e = last;
                if (e == null || e.storage() != s) last = e = new FabricEndpoint(s);
                return e;
            }
        };
    }

    private record FabricEndpoint(EnergyStorage storage) implements Endpoint {

        @Override
        public int extractableEnergy() {
            if (!storage.supportsExtraction()) return 0;
            // Simulate: extract the max inside a transaction we never commit, so it rolls back.
            try (Transaction tx = Transaction.openOuter()) {
                return (int) Math.min(storage.extract(Integer.MAX_VALUE, tx), Integer.MAX_VALUE);
            }
        }

        @Override
        public int moveTo(Endpoint dest, int maxEnergy) {
            if (maxEnergy <= 0) return 0;
            EnergyStorage target = ((FabricEndpoint) dest).storage;
            // A null transaction tells the helper to open and commit its own.
            return (int) EnergyStorageUtil.move(storage, target, maxEnergy, null);
        }
    }
}
