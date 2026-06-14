package com.kestalkayden.hydrofarm.util;

import net.minecraft.core.BlockPos;

/** Deterministic, position-derived staggering of periodic block-entity work.
 *
 *  <p>Block entities that all tick, re-walk a graph, or refresh a cache on the SAME game tick produce
 *  a synchronized spike — every bed in a farm re-BFSing at once, every pump on a network re-walking in
 *  lockstep the tick after a topology edit. Folding a block position into a stable per-position phase
 *  spreads that work evenly across the window instead. The result is a pure function of the position,
 *  so it survives save/load and needs no storage.
 *
 *  <p>{@link #mix(long)} is the shared primitive: a multiply by the 64-bit golden ratio (Fibonacci
 *  hashing), which decorrelates the low bits of neighbouring positions. {@link #offset} folds that
 *  into {@code [0, period)} for the common "stagger a periodic task" case; callers needing several
 *  decorrelated values from one position {@code mix} once and slice different bit ranges. */
public final class PositionStagger {

    /** 2^64/φ, odd — the Fibonacci-hashing multiplier. Spreads sequential keys (adjacent block
     *  positions differ by 1 in a packed coordinate) across the 64-bit range so the values derived
     *  from them don't cluster. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private PositionStagger() {}

    /** The raw golden-ratio mix of {@code key}. May be negative (the multiply wraps); {@link #offset}
     *  and bit-slicing callers handle that. */
    public static long mix(long key) {
        return key * GOLDEN;
    }

    /** A stable offset in {@code [0, period)} derived from {@code key}; 0 when {@code period <= 1}. */
    public static int offset(long key, int period) {
        if (period <= 1) return 0;
        return (int) Math.floorMod(mix(key), (long) period);
    }

    /** {@link #offset(long, int)} keyed by a packed block position. */
    public static int offset(BlockPos pos, int period) {
        return offset(pos.asLong(), period);
    }
}
