package com.kestalkayden.hydrofarm.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Platform seam for moving energy between neighbouring blocks, mirroring {@link FluidApi} and
 *  {@link ItemApi}. Energy is typeless — a single integer quantity — so an endpoint only needs to
 *  report how much can be pulled and to perform an atomic move. Implemented per loader (Fabric
 *  Team Reborn Energy, NeoForge EnergyHandler) and resolved via {@link HydrofarmPlatform#energy()}.
 *
 *  <p>This seam only touches <em>other</em> blocks; a Hydrofarm block exposing its own energy
 *  buffer (generator, autocrafter) registers its capability in the loader tree, so the shared
 *  mover logic (the energy pump) stays in {@code common}. */
public interface EnergyApi {

    /** Energy endpoint on {@code side} of the block at {@code pos}, or {@code null} if none. */
    Endpoint find(Level level, BlockPos pos, Direction side);

    /** A repeat-lookup cache for the endpoint on {@code side} of {@code pos}, backed by the
     *  loader's purpose-built cache (NeoForge {@code BlockCapabilityCache} / Fabric
     *  {@code BlockApiCache}) — those skip the per-lookup chunk/BE/provider resolution that
     *  {@link #find} pays every call. Create ONCE per stable lookup site (a terminal face, a
     *  cached network source) and hold it; creating one costs about as much as a find().
     *  Server-side only: both loader caches require a ServerLevel. */
    EndpointCache cache(ServerLevel level, BlockPos pos, Direction side);

    /** A held resolver for one (pos, side). {@link #get()} revalidates cheaply — the loader
     *  invalidates internally when the block there changes — and returns the current endpoint,
     *  or null while none exists. Safe to call every burst. */
    interface EndpointCache {
        Endpoint get();
    }

    /** A resolved energy store at one block face. Amounts are plain energy units; the loader
     *  implementation manages its own transaction so callers never see platform transaction types. */
    interface Endpoint {

        /** Energy currently extractable from this endpoint (simulated) — lets a drain pump skip the
         *  network walk when the source is empty. */
        int extractableEnergy();

        /** Atomically move up to {@code maxEnergy} from this endpoint into {@code dest} — extract
         *  then insert in a single transaction, all-or-nothing per the loader helper. Returns the
         *  amount moved. {@code dest} must originate from the same loader (the impl may cast it). */
        int moveTo(Endpoint dest, int maxEnergy);
    }
}
