package com.xploits.pvp.crystal.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Where you could be by the time a crystal explodes (task R3-16, research-reserve-undershoot): the budget
 * judges each crystal's self damage from your position when you decide, but the explosion itself happens
 * {@code landingTicks} pre-ticks later (the brain's own learned landing time, R3-11, or a documented bound
 * while too little is known yet), by when you may have moved. Reading only your position now undershoots the
 * reserve whenever you are the one moving (the circling and strafing bench scenarios, R3-14).
 *
 * <p>Always included, at your current height AND at a jump's height (task R3-16 fix round 1: a crystal you
 * could both move toward and jump to at once, above you and off to one side, must be judged with both effects
 * together, not the closer of either alone — {@code ExplosionMath#rawDamage} is monotonic in distance, so a
 * combined point can be strictly closer than either): standing exactly where you are now ({@code (0, h, 0)});
 * the straight line your current horizontal velocity already has you on, projected the full {@code
 * landingTicks} ({@code (vx * landingTicks, h, vz * landingTicks)}). Since you could also turn onto any other
 * line before the crystal explodes, a small ring of {@value #RING_POINTS} points, evenly spaced, at the same
 * horizontal distance you could cover ({@code speed * landingTicks}, speed the length of your current
 * horizontal velocity) covers every other direction: no turn you could make ends up more than one
 * {@value #RING_POINTS}-th of a full turn away from a sampled point. {@code h} is 0 (standing) or
 * {@value #JUMP_HEIGHT} blocks up (vanilla's own jump height, the same value the bench's own self-motion
 * scripts jump, {@code CircleWalk.JUMP_HEIGHT}), so every horizontal point above is checked at both heights:
 * up to {@code 2 * (2 + }{@value #RING_POINTS}{@code )} points in total, still bounded and still cheap (see
 * below), never a per-height multiplication of anything else.
 *
 * <p>Kept deliberately small (task R3-12: an exposure raycast is expensive) and, more importantly, cheap to
 * check at all: {@link #worstRawDamage} never raycasts any of these extra points. It reads them at the worst
 * exposure an explosion can ever have (1.0) instead, which can only ever read a self damage at or above the
 * true one for that point, never below — the one real raycast a caller still needs (for wherever you stand
 * right now, the one point whose real exposure is worth knowing) is its own, unaffected by this class.
 *
 * <p><b>Total: never throws on any {@code double}/{@code long} input</b> (task R3-16 fix round 1). A
 * non-finite velocity is treated as no velocity (0), and a negative {@code landingTicks} as 0 pre-ticks away —
 * both the most cautious finite reading, matching what {@link #offsets} would compute for a player who is not
 * moving. This is defence in depth: the adapter is expected to sanitise a broken reading itself before it
 * ever reaches here (nothing here should normally see either case), but a pure core that decides a safety
 * budget must never be the thing that crashes the tick handler on bad game input, whatever the caller does.
 */
public final class MovementReach {
    /** Ring points around you, evenly spaced, at {@code speed * landingTicks}. */
    public static final int RING_POINTS = 8;
    /** Vanilla's own jump height, in blocks. */
    public static final double JUMP_HEIGHT = 1.25;
    /** The heights the ring and the projection are both checked at: standing, and a jump's height. */
    private static final double[] HEIGHTS = {0, JUMP_HEIGHT};

    private MovementReach() {
    }

    /** One offset from your current position: horizontal {@code dx}/{@code dz}, vertical {@code dy}. */
    public record Offset(double dx, double dy, double dz) {
    }

    /**
     * Every offset to check, for a horizontal velocity of {@code (vx, vz)} blocks/tick and a landing
     * {@code landingTicks} pre-ticks away, at both {@link #HEIGHTS}: always standing still and the velocity
     * projection; with a nonzero speed, {@value #RING_POINTS} ring points too. Never throws (see the class
     * javadoc): a non-finite {@code vx}/{@code vz} reads as 0, and a negative {@code landingTicks} as 0.
     */
    public static List<Offset> offsets(double vx, double vz, long landingTicks) {
        double safeVx = Double.isFinite(vx) ? vx : 0;
        double safeVz = Double.isFinite(vz) ? vz : 0;
        long ticks = Math.max(0, landingTicks);
        double speed = Math.hypot(safeVx, safeVz);
        double radius = speed * ticks;
        List<Offset> points = new ArrayList<>();
        for (double h : HEIGHTS) {
            points.add(new Offset(0, h, 0));
            points.add(new Offset(safeVx * ticks, h, safeVz * ticks));
            if (radius > 0) {
                for (int i = 0; i < RING_POINTS; i++) {
                    double angle = 2 * Math.PI * i / RING_POINTS;
                    points.add(new Offset(radius * Math.cos(angle), h, radius * Math.sin(angle)));
                }
            }
        }
        return List.copyOf(points);
    }

    /**
     * The worst raw damage ({@link ExplosionMath#rawDamage}, before armour, exposure assumed 1.0: the most
     * any explosion could deal) an explosion at {@code (ex, ey, ez)} relative to your feet now could deal
     * from anywhere in {@link #offsets(double, double, long)}. Never throws (see the class javadoc): a
     * non-finite {@code ex}/{@code ey}/{@code ez} propagates a NaN result, exactly as {@link
     * ExplosionMath#rawDamage} already documents for a single point, never an exception.
     *
     * @param ex the explosion's x minus your feet's x now (never printed: a difference, not a position)
     * @param ey the explosion's y minus your feet's y now
     * @param ez the explosion's z minus your feet's z now
     */
    public static float worstRawDamage(double ex, double ey, double ez, double vx, double vz, long landingTicks) {
        float worst = 0f;
        for (Offset o : offsets(vx, vz, landingTicks)) {
            double dx = ex - o.dx();
            double dy = ey - o.dy();
            double dz = ez - o.dz();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            worst = Math.max(worst, ExplosionMath.rawDamage(distance, 1.0));
        }
        return worst;
    }
}
