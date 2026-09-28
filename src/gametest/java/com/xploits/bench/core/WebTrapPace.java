package com.xploits.bench.core;

/**
 * Task A2+ requirement 5 ("webs + trap"): two independent cooldowns the fight opponent uses on the real
 * player — a cobweb at the player's feet block if free, and a head trap (the block above the player's head
 * plus the four blocks around it) where free. Pure and deterministic: only the cadence lives here; which
 * blocks are free and where they go is the adapter's own vanilla-world reading.
 */
public final class WebTrapPace {
    /** A cobweb is placed at most this often. */
    public static final int WEB_EVERY = 100;
    /** A head trap is placed at most this often. */
    public static final int TRAP_EVERY = 200;

    private WebTrapPace() {
    }

    /** Whether the web should fire at {@code sinceT0}: every {@value #WEB_EVERY} ticks, never at T0. */
    public static boolean webDueAt(int sinceT0) {
        return PeriodicTrigger.dueAt(sinceT0, WEB_EVERY);
    }

    /** Whether the trap should fire at {@code sinceT0}: every {@value #TRAP_EVERY} ticks, never at T0. */
    public static boolean trapDueAt(int sinceT0) {
        return PeriodicTrigger.dueAt(sinceT0, TRAP_EVERY);
    }
}
