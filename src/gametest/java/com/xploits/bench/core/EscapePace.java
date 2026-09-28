package com.xploits.bench.core;

/**
 * Task A2+ requirement 6 ("movement + escape"): at {@value #ESCAPE_TOTEMS} totems left or fewer, the fight
 * opponent moves {@value #ESCAPE_DISTANCE} blocks away over {@value #ESCAPE_TICKS} ticks, as a thrown pearl
 * would — a fixed direction picked once, a steady speed, covering the whole distance in that many ticks, not
 * before and not drifting past it. Pure and deterministic: the adapter picks the direction (away from the
 * player, at the trigger tick) and calls {@link #displacement} for how far along it the opponent should have
 * moved by now.
 */
public final class EscapePace {
    /** Totems left (offhand plus spare) at or below which the opponent escapes instead of fighting on. */
    public static final int ESCAPE_TOTEMS = 2;
    /** Total distance the escape covers, in its fixed direction. */
    public static final int ESCAPE_DISTANCE = 12;
    /** Ticks the escape takes to cover the whole distance. */
    public static final int ESCAPE_TICKS = 10;
    private static final double SPEED_PER_TICK = (double) ESCAPE_DISTANCE / ESCAPE_TICKS;

    private EscapePace() {
    }

    /** Whether {@code totemsLeft} is low enough to trigger the escape. */
    public static boolean triggered(int totemsLeft) {
        return totemsLeft <= ESCAPE_TOTEMS;
    }

    /**
     * Blocks covered along the escape's fixed direction, {@code ticksSinceTrigger} ticks after it began (0 at
     * and before the trigger tick itself): a steady ramp reaching {@value #ESCAPE_DISTANCE} at
     * {@value #ESCAPE_TICKS} ticks and staying there after (it keeps the distance it reached, never overshoots).
     */
    public static double displacement(int ticksSinceTrigger) {
        if (ticksSinceTrigger <= 0) return 0;
        return Math.min(ESCAPE_DISTANCE, SPEED_PER_TICK * ticksSinceTrigger);
    }
}
