package com.xploits.pvp.crystal.core;

import java.util.OptionalDouble;

/**
 * How far you have really moved, tick by tick (task B3): the horizontal displacement of your feet between
 * consecutive pre-ticks, for the last {@value #CAPACITY}. {@link #maxDisplacement} answers "what is the farthest
 * you actually got from where you were, over any run of up to {@code ticks} consecutive pre-ticks of that history", the
 * measured figure {@link MovementReach} sizes its ring from, so that circling (whose chord over the landing time
 * is shorter than {@code speed * ticks}) is not judged as if you ran in a straight line.
 *
 * <p>Pure and total: a non-finite displacement is recorded as 0 (the same reading the adapter's velocity gives
 * a broken position), never an exception. Horizontal only: the vertical reach is {@link MovementReach#heights}'s.
 */
public final class MovementHistory {
    /** Pre-ticks kept: two seconds. A landing bound longer than this can never be measured, only assumed. */
    public static final int CAPACITY = 40;

    private final double[] dx = new double[CAPACITY];
    private final double[] dz = new double[CAPACITY];
    private int size;
    private int next;

    /** Records the displacement of this pre-tick, replacing the oldest once {@value #CAPACITY} are held. */
    public void record(double moveX, double moveZ) {
        dx[next] = Double.isFinite(moveX) && Double.isFinite(moveZ) ? moveX : 0;
        dz[next] = Double.isFinite(moveX) && Double.isFinite(moveZ) ? moveZ : 0;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    /** Forgets everything (a new activation says nothing about how you move now). */
    public void reset() {
        size = 0;
        next = 0;
    }

    /** How many pre-ticks are held, at most {@value #CAPACITY}. */
    public int size() {
        return size;
    }

    /**
     * The largest horizontal distance between where you were and where you got to over any {@code ticks} or
     * fewer consecutive recorded pre-ticks (the crystal can explode before the bound). Empty while fewer than {@code ticks} are held (or {@code ticks} exceeds
     * {@value #CAPACITY}): nothing measured yet, the caller keeps its assumption. A {@code ticks} of 0 or less is
     * a distance of 0.
     */
    public OptionalDouble maxDisplacement(int ticks) {
        if (ticks <= 0) return OptionalDouble.of(0);
        if (ticks > size) return OptionalDouble.empty();
        int oldest = (next - size + CAPACITY) % CAPACITY;
        // Any length up to `ticks`, not only `ticks`: the crystal can explode earlier than the bound, and a
        // path that curves back (circling) is farther from where it began after half the time than after all.
        double best = 0;
        for (int start = 0; start < size; start++) {
            double sx = 0;
            double sz = 0;
            for (int i = 0; i < ticks && start + i < size; i++) {
                int at = (oldest + start + i) % CAPACITY;
                sx += dx[at];
                sz += dz[at];
                best = Math.max(best, Math.hypot(sx, sz));
            }
        }
        return OptionalDouble.of(best);
    }
}
