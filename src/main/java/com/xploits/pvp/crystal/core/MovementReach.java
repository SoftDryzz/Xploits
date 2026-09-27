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
 * <p>Always included: standing exactly where you are now ({@code (0, 0, 0)}); the straight line your current
 * horizontal velocity already has you on, projected the full {@code landingTicks}. Since you could also turn
 * onto any other line before the crystal explodes, a small ring of {@value #RING_POINTS} points, evenly
 * spaced, at the same horizontal distance you could cover ({@code speed * landingTicks}, speed the length of
 * your current horizontal velocity) covers every other direction: no turn you could make ends up more than
 * one {@value #RING_POINTS}-th of a full turn away from a sampled point. One vertical point, {@value
 * #JUMP_HEIGHT} blocks up (vanilla's own jump height, the same value the bench's own self-motion scripts
 * jump, {@code CircleWalk.JUMP_HEIGHT}), covers a jump changing your exposure to a crystal above or below
 * you.
 *
 * <p>Kept deliberately small (task R3-12: an exposure raycast is expensive) and, more importantly, cheap to
 * check at all: {@link #worstRawDamage} never raycasts any of these extra points. It reads them at the worst
 * exposure an explosion can ever have (1.0) instead, which can only ever read a self damage at or above the
 * true one for that point, never below — the one real raycast a caller still needs (for wherever you stand
 * right now, the one point whose real exposure is worth knowing) is its own, unaffected by this class.
 */
public final class MovementReach {
    /** Ring points around you, evenly spaced, at {@code speed * landingTicks}. */
    public static final int RING_POINTS = 8;
    /** Vanilla's own jump height, in blocks. */
    public static final double JUMP_HEIGHT = 1.25;

    private MovementReach() {
    }

    /** One offset from your current position: horizontal {@code dx}/{@code dz}, vertical {@code dy}. */
    public record Offset(double dx, double dy, double dz) {
    }

    /**
     * Every offset to check, for a horizontal velocity of {@code (vx, vz)} blocks/tick and a landing
     * {@code landingTicks} pre-ticks away: always standing still, the velocity projection and the jump
     * point; with a nonzero speed, {@value #RING_POINTS} ring points too.
     */
    public static List<Offset> offsets(double vx, double vz, long landingTicks) {
        if (!Double.isFinite(vx) || !Double.isFinite(vz)) throw new IllegalArgumentException("velocity not finite");
        if (landingTicks < 0) throw new IllegalArgumentException("landing ticks " + landingTicks);
        List<Offset> points = new ArrayList<>();
        points.add(new Offset(0, 0, 0));
        points.add(new Offset(vx * landingTicks, 0, vz * landingTicks));
        double speed = Math.hypot(vx, vz);
        double radius = speed * landingTicks;
        if (radius > 0) {
            for (int i = 0; i < RING_POINTS; i++) {
                double angle = 2 * Math.PI * i / RING_POINTS;
                points.add(new Offset(radius * Math.cos(angle), 0, radius * Math.sin(angle)));
            }
        }
        points.add(new Offset(0, JUMP_HEIGHT, 0));
        return List.copyOf(points);
    }

    /**
     * The worst raw damage ({@link ExplosionMath#rawDamage}, before armour, exposure assumed 1.0: the most
     * any explosion could deal) an explosion at {@code (ex, ey, ez)} relative to your feet now could deal
     * from anywhere in {@link #offsets(double, double, long)}.
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
