package com.xploits.bench.core;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * A walk back and forth along one straight path (crystal-aura++ R3-5, the fight situations): leg 0 goes from
 * the start to the far end at a steady speed, then the walker stands for that leg's pause, leg 1 comes back,
 * stands for its pause, and so on. The legs keep their exact length even when it is not a whole number of
 * ticks, so the walk never drifts. Pure and deterministic: the same arguments give the same walk.
 */
public final class Shuttle {
    /**
     * A pause sequence that looks random but is fixed (so runs are reproducible): ticks to stand at an end,
     * each within 0-20, taken in turn, leg after leg, and repeated.
     */
    public static final List<Integer> IRREGULAR_PAUSES = List.of(7, 16, 3, 20, 11, 0, 14, 5, 18, 9, 1, 12);

    private final double length;
    private final double speed;
    private final IntUnaryOperator pauses;
    private final double legTicks;

    /**
     * @param length the path's length, in blocks
     * @param speed  blocks walked a tick
     * @param pauses the ticks to stand at the end a leg arrives at, by leg index (0 is the first leg out)
     */
    public Shuttle(double length, double speed, IntUnaryOperator pauses) {
        if (!(length > 0)) throw new IllegalArgumentException("the path needs a length");
        if (!(speed > 0)) throw new IllegalArgumentException("the walk needs a speed");
        this.length = length;
        this.speed = speed;
        this.pauses = pauses;
        this.legTicks = length / speed;
    }

    /** The pause after leg {@code leg} in {@link #IRREGULAR_PAUSES}. */
    public static int irregularPause(int leg) {
        return IRREGULAR_PAUSES.get(Math.floorMod(leg, IRREGULAR_PAUSES.size()));
    }

    /** How far from the start, along the path, the walker is {@code tick} ticks after T0 (the start before it). */
    public double offset(int tick) {
        return state(tick)[0];
    }

    /** +1 on a leg out, -1 on a leg back, 0 standing at an end (and before T0). */
    public int direction(int tick) {
        return (int) state(tick)[1];
    }

    /** {offset, direction} at {@code tick}. */
    private double[] state(int tick) {
        if (tick <= 0) return new double[] {0, tick < 0 ? 0 : 1};
        double t = tick;
        for (int leg = 0; ; leg++) {
            boolean out = leg % 2 == 0;
            if (t < legTicks) {
                double walked = t * speed;
                return new double[] {out ? walked : length - walked, out ? 1 : -1};
            }
            t -= legTicks;
            int pause = pauses.applyAsInt(leg);
            if (pause < 0) throw new IllegalArgumentException("a pause of " + pause + " ticks");
            if (t < pause) return new double[] {out ? length : 0, 0};
            t -= pause;
        }
    }
}
