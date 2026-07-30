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
 *  <p>{@link #mix(long)} is the shared primitive: a golden-ratio multiply followed by a murmur3
 *  finalizer. {@link #offset} folds that into {@code [0, period)} for the common "stagger a periodic
 *  task" case; callers needing several decorrelated values from one position {@code mix} once and
 *  slice different bit ranges.
 *
 *  <p><b>The finalizer is load-bearing, do not remove it.</b> A bare multiply has no downward
 *  avalanche: output bit <i>k</i> depends only on input bits 0..<i>k</i>. {@link #offset} takes the
 *  result mod {@code period}, and every period in this mod is a power of two, so it keeps only the
 *  low bits — which for a bare multiply depend only on the low bits of the key. {@link BlockPos#asLong}
 *  packs Y into bits 0-11, so {@code offset(pos, 16)} degenerated to a pure function of Y: every block
 *  in a single-layer structure got the SAME phase and the stagger did nothing at all. The xor-shifts
 *  below are what push high-bit entropy down into the low bits. (Odd periods were unaffected, which
 *  is why this hid for so long — only the power-of-two callers were dead.) */
public final class PositionStagger {

    /** 2^64/φ, odd — the Fibonacci-hashing multiplier. Spreads sequential keys (adjacent block
     *  positions differ by 1 in a packed coordinate) across the HIGH bits of the 64-bit range. The
     *  finalizer in {@link #mix} is what makes that spread visible in the low bits too. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    // murmur3 64-bit finalizer constants — same avalanche used by AnimalPenRoaming#mix.
    private static final long FMIX_1 = 0xFF51AFD7ED558CCDL;
    private static final long FMIX_2 = 0xC4CEB9FE1A85EC53L;

    private PositionStagger() {}

    /** Full-avalanche mix of {@code key}: every output bit depends on every input bit. May be negative
     *  (the multiplies wrap); {@link #offset} and bit-slicing callers handle that. */
    public static long mix(long key) {
        long z = key * GOLDEN;
        z = (z ^ (z >>> 33)) * FMIX_1;
        z = (z ^ (z >>> 33)) * FMIX_2;
        return z ^ (z >>> 33);
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
