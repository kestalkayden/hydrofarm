package com.kestalkayden.hydrofarm.util;

/** Exponential re-probe back-off for a periodically-retried action — a pump face with nothing to
 *  move, a generator with no acceptor, a sink on a dead grid. After a futile attempt the cooldown
 *  ramps {@code base, 2×base, 4×base, …} (capped), so an idle or jammed actor stops grinding the
 *  network every cycle; any success — or an external {@link #reset()} on a topology change or full
 *  buffer — clears it for an immediate retry.
 *
 *  <p>One instance per independently-backing-off actor (per pump face, per block entity). Pure int
 *  state, no allocation on the hot path. The cooldown is measured in whatever unit the caller debits
 *  through {@link #ready(int)}: callers that retry every tick pass 1; callers that only attempt every
 *  N ticks pass N, so the ramp stays expressed in ticks regardless of cadence. */
public final class Backoff {

    private final int base;
    private final int max;
    private final int rampCap;
    private int cooldown;
    private int futileStreak;

    public Backoff(int base, int max, int rampCap) {
        this.base = base;
        this.max = max;
        this.rampCap = rampCap;
    }

    /** Advance one cycle, debiting {@code step} from the cooldown. Returns true when the cooldown has
     *  elapsed and the caller should attempt its action this cycle; false while still backing off. */
    public boolean ready(int step) {
        if (cooldown > 0) {
            cooldown -= step;
            return false;
        }
        return true;
    }

    /** {@link #ready(int)} for callers that retry every cycle (step = 1). */
    public boolean ready() {
        return ready(1);
    }

    /** The attempt moved something — clear the back-off so the next cycle retries immediately. */
    public void recordMoved() {
        reset();
    }

    /** The attempt moved nothing — ramp the cooldown (base, 2×, 4×, … capped at {@code max}). */
    public void recordFutile() {
        futileStreak++;
        cooldown = Math.min(max, base << Math.min(futileStreak - 1, rampCap));
    }

    /** Force an immediate retry next cycle — e.g. the network topology changed or the buffer filled. */
    public void reset() {
        futileStreak = 0;
        cooldown = 0;
    }
}
