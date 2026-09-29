package com.xploits.bench.core;

import java.util.function.DoublePredicate;

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

    /**
     * Fix round 1 (review-a2plus.md, Critical): the largest non-negative displacement along the horizontal
     * direction {@code (dirX, dirZ)} from {@code (startX, startZ)} that keeps both coordinates within
     * {@code [-halfExtent, halfExtent]} — a square bound (the cleared arena floor around its centre) — never
     * past it, whatever the direction: a ray/box clamp along the escape's own straight line, not a per-axis
     * clamp of the final point (which would bend the path at a corner instead of stopping it on the line).
     * {@code ESCAPE_DISTANCE} (12) is larger than the arena's own floor radius (10), so every escape needs
     * this: without it, every trigger would send the opponent past the cleared floor.
     *
     * @throws IllegalArgumentException {@code halfExtent} is not positive, or {@code (dirX, dirZ)} is the zero
     *                                  vector (no direction to clamp along)
     */
    public static double maxDisplacement(double startX, double startZ, double dirX, double dirZ, double halfExtent) {
        if (!(halfExtent > 0)) throw new IllegalArgumentException("the bound must be positive");
        if (dirX == 0 && dirZ == 0) throw new IllegalArgumentException("the escape needs a direction");
        double tx = axisLimit(startX, dirX, halfExtent);
        double tz = axisLimit(startZ, dirZ, halfExtent);
        return Math.max(0, Math.min(tx, tz));
    }

    /** How far {@code start} can move along {@code dir} before {@code start + dir * t} passes {@code ±halfExtent}. */
    private static double axisLimit(double start, double dir, double halfExtent) {
        if (dir > 0) return (halfExtent - start) / dir;
        if (dir < 0) return (-halfExtent - start) / dir;
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Fix round 1 (review-a2plus.md, Critical): the distance to land at, trying {@code idealDisplacement}
     * first, then one block nearer at a time, down to (and always including) 0 — the trigger point itself,
     * always safe to land on since the opponent already stood there — the first one {@code free} accepts.
     * {@code free} is the caller's own vanilla-world check (two free blocks, solid ground under them); this
     * function only picks among the candidates it offers, nearest-to-ideal first, and never asks it about 0
     * (nothing to check: the opponent is already standing there). Deterministic and never returns a value it
     * did not either find free or fall back to.
     *
     * @throws IllegalArgumentException {@code idealDisplacement} is negative
     */
    public static double landingDisplacement(double idealDisplacement, DoublePredicate free) {
        if (idealDisplacement < 0) throw new IllegalArgumentException("a displacement cannot be negative");
        double t = idealDisplacement;
        while (t > 0) {
            if (free.test(t)) return t;
            t -= 1.0;
        }
        return 0;
    }
}
