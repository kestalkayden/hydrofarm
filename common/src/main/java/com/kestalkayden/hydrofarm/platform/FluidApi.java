package com.kestalkayden.hydrofarm.platform;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

/** Platform seam for reading and moving fluids in <em>neighbouring</em> blocks, denominated
 *  uniformly in millibuckets. Implemented per loader — Fabric's Transfer API (with droplet
 *  conversion) and NeoForge's capability/transfer API — and resolved via
 *  {@link HydrofarmPlatform#fluids()}.
 *
 *  <p>A block exposing its <em>own</em> storage stays in the loader trees (the Storage /
 *  capability wrapper registered at init); this seam only touches other blocks, so the shared
 *  mover logic (pumps, sprinkler, XP drain) can live in {@code common}. */
public interface FluidApi {

    /** Fluid endpoint on {@code side} of the block at {@code pos}, or {@code null} if none. */
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

    /** The fluid carried by {@code stack} (e.g. a bucket), or {@code Fluids.EMPTY} if it holds
     *  none. Used by pump filter slots to resolve a filter item to a fluid type. */
    Fluid fluidInItem(ItemStack stack);

    /** A resolved fluid store at one block face. Every amount is in millibuckets; the loader
     *  implementation converts to/from its native unit (Fabric droplets / NeoForge mB) and
     *  manages its own transaction internally so callers never see platform transaction types. */
    interface Endpoint {

        /** Distinct non-empty fluids currently held — used for pump candidate discovery. */
        List<Fluid> fluids();

        /** Insert up to {@code maxMb} of {@code fluid}; returns mB accepted (committed). */
        int insert(Fluid fluid, int maxMb);

        /** Extract up to {@code maxMb} of {@code fluid}; returns mB removed (committed). */
        int extract(Fluid fluid, int maxMb);

        /** Atomically move up to {@code maxMb} of {@code fluid} from this endpoint into
         *  {@code dest} — extract-then-insert in a single transaction, rolled back entirely
         *  unless the full hop succeeds. Returns mB moved. {@code dest} must originate from the
         *  same loader (the implementation may cast it). */
        int moveTo(Endpoint dest, Fluid fluid, int maxMb);
    }
}
