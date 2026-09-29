package com.xploits.bench.core;

/**
 * Task A2+: a periodic on/off cooldown shared by several of the fight opponent's behaviours (spot blocking,
 * hole fill, webs, trap) — each names its own {@code *_EVERY} constant, but the "every N ticks, starting at
 * N, never at T0 itself" rule is the same one every time, so it lives here once. Pure and deterministic.
 */
public final class PeriodicTrigger {
    private PeriodicTrigger() {
    }

    /**
     * Whether {@code period}-ticked behaviour fires at {@code sinceT0}: the first time is {@code period}
     * ticks after T0, then every {@code period} ticks after that; never before or at T0 itself (T0 is the
     * fight's opening tick, not an attack of its own).
     *
     * @throws IllegalArgumentException {@code period} is not positive
     */
    public static boolean dueAt(int sinceT0, int period) {
        if (period <= 0) throw new IllegalArgumentException("a period must be positive");
        return sinceT0 > 0 && sinceT0 % period == 0;
    }
}
