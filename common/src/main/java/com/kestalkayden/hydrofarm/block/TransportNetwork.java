package com.kestalkayden.hydrofarm.block;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;

import com.kestalkayden.hydrofarm.util.PositionStagger;

/** Global structure nonce for the pipe/pump transport networks. Drain pumps cache their network
 *  walk (the set of reachable fill pumps) keyed by this epoch; any pipe/pump connection forming or
 *  breaking — or a pump toggling FILL/DRAIN — bumps it, so every cached pump re-walks on its next
 *  tick. One shared epoch covers both the fluid and item networks: cross-type over-invalidation (a
 *  water-pipe edit nudging an item pump) is a single extra BFS, negligible, and keeps the surface
 *  minimal.
 *
 *  <p>Mirrors {@link AbstractClusterBedBlockEntity#bumpClusterEpoch()} for the bed clusters. */
public final class TransportNetwork {

    private static final AtomicLong EPOCH = new AtomicLong();

    private TransportNetwork() {}

    /** Current network epoch. A pump's cached fill-pump set is valid only while this is unchanged. */
    public static long epoch() {
        return EPOCH.get();
    }

    /** Invalidate every pump's cached network — called when a pipe/pump connection forms or breaks,
     *  or a pump toggles FILL/DRAIN. Bumping from the client side is harmless (no pump ticks there). */
    public static void bump() {
        EPOCH.incrementAndGet();
    }

    /** Spread (ticks) of the per-node jitter added to a transport node's network-cache TTL. An epoch
     *  bump resets every node's cache to the same tick; without jitter a fixed TTL would then re-walk
     *  the whole network on the SAME tick every TTL thereafter — a synchronized O(nodes × network)
     *  spike. Mirrors the cluster beds' position-jittered cache TTL. */
    private static final int CACHE_TTL_JITTER = 16;

    /** Deterministic per-position jitter in {@code [0, CACHE_TTL_JITTER)} for a node's network-cache
     *  TTL, so nodes re-walk on staggered ticks instead of in lockstep. See {@link #CACHE_TTL_JITTER}. */
    public static long cacheTtlJitter(BlockPos pos) {
        return PositionStagger.offset(pos, CACHE_TTL_JITTER);
    }
}
