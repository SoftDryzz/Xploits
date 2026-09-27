package com.xploits.bench.core;

/**
 * The path of self-circle (crystal-aura++ R3-14): the offset from the centre of a circle of radius
 * {@value #RADIUS} walked at {@value #SPEED} blocks/s, counter-clockwise seen from above, starting at the
 * point nearest {@code +x}, jumping every {@value #JUMP_EVERY} ticks from tick {@value #FIRST_JUMP} (a
 * parabola {@value #JUMP_HEIGHT} blocks high over {@value #JUMP_TICKS} ticks). These mirror the sparring's
 * own {@code Circler} script exactly (same radius, speed and jump), duplicated here rather than shared so
 * the pure core stays free of anything outside {@code bench.core}. Pure and deterministic: the same tick
 * always gives the same offset.
 */
public final class CircleWalk {
    public static final double RADIUS = 1.5;
    /** Blocks per second along the circle. */
    public static final double SPEED = 4.3;
    /** Radians per tick (SPEED / RADIUS rad/s over 20 ticks). */
    private static final double RADIANS_PER_TICK = SPEED / RADIUS / 20;
    public static final int FIRST_JUMP = 10;
    public static final int JUMP_EVERY = 20;
    public static final int JUMP_TICKS = 12;
    public static final double JUMP_HEIGHT = 1.25;

    private CircleWalk() {
    }

    /** The offset's x at tick {@code k} after T0 (held at tick 0's value before it): {@code r cos(a)}. */
    public static double offsetX(int k) {
        return RADIUS * Math.cos(angle(k));
    }

    /** The offset's z at tick {@code k} after T0 (held at tick 0's value before it): {@code -r sin(a)}. */
    public static double offsetZ(int k) {
        return -RADIUS * Math.sin(angle(k));
    }

    /**
     * {@code a(k) = pi + k * radians/tick}: a growing {@code k} turns +x toward -z, which is
     * counter-clockwise seen from above; {@code a = pi} is the point nearest {@code +x}.
     */
    private static double angle(int k) {
        return Math.PI + RADIANS_PER_TICK * Math.max(0, k);
    }

    /** Height above the floor at tick {@code k}: a parabola peaking at {@value #JUMP_HEIGHT} in the middle
     * of each {@value #JUMP_TICKS}-tick jump, 0 the rest of the time and before {@value #FIRST_JUMP}. */
    public static double jumpHeight(int k) {
        if (k < FIRST_JUMP) return 0;
        int t = (k - FIRST_JUMP) % JUMP_EVERY;
        if (t >= JUMP_TICKS) return 0;
        double f = (double) t / JUMP_TICKS;
        return 4 * JUMP_HEIGHT * f * (1 - f);
    }

    /** Whether tick {@code k} is mid-jump: {@link #jumpHeight(int)} is above 0, so the ground state is false. */
    public static boolean airborne(int k) {
        return jumpHeight(k) > 0;
    }
}
