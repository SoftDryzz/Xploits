package com.xploits.pvp.crystal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <p>Kept small (task R3-12: an exposure raycast is expensive) and cheap to check.
 * {@link #worstRawDamage(double, double, double, double, double, long)} reads every point at the worst exposure an
 * explosion can ever have (1.0), which can only read a self damage at or above the true one. Since task B2 the
 * exact worst case reads the real exposure at these points by branch and bound ({@link #rankedByWorstRaw},
 * {@link #worstRawDamage(List, ExposureFunction, float)}): ranked by that ceiling, the search stops at the first
 * point that cannot beat the best real value found, so only a few raycasts are spent. Task C2 added the ring's arcs
 * and every point's halfway points ({@link #reachPoints}), since cover at two neighbouring points can hide open
 * ground between them.
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
     * The ring's arc midpoints (task C2, final review I3): with a nonzero speed, {@value #RING_POINTS} more points at
     * the ring's radius, each half way (in angle) between two neighbouring ring points, at both {@link #HEIGHTS}.
     * Neighbouring ring points are about 0.77 of the radius apart, so at real exposure cover at both could hide
     * open ground on the arc between them, at the same distance from you, and the worst case would be read too low.
     * With these, no point of the ring is farther than a sixteenth of a turn (0.39 of the radius) from a sampled
     * one; {@link #withMidpoints} then adds their radial midpoints. Empty while standing still. Never throws, same
     * input contract as {@link #offsets}.
     */
    public static List<Offset> arcMidpoints(double vx, double vz, long landingTicks) {
        double safeVx = Double.isFinite(vx) ? vx : 0;
        double safeVz = Double.isFinite(vz) ? vz : 0;
        double radius = Math.hypot(safeVx, safeVz) * Math.max(0, landingTicks);
        if (!(radius > 0)) return List.of();
        List<Offset> points = new ArrayList<>();
        for (double h : HEIGHTS) {
            for (int i = 0; i < RING_POINTS; i++) {
                double angle = 2 * Math.PI * (i + 0.5) / RING_POINTS;
                points.add(new Offset(radius * Math.cos(angle), h, radius * Math.sin(angle)));
            }
        }
        return List.copyOf(points);
    }

    /**
     * Every point the exact worst case reads at real exposure (task C2): {@link #offsets} and the ring's {@link
     * #arcMidpoints}, each with its halfway points ({@link #withMidpoints}). Up to {@code 4 * 2 * (2 + 2 *
     * RING_POINTS)} = {@code 144} points, which costs almost nothing: they are ranked by their exposure-1.0 raw
     * damage and the search stops at the first whose ceiling cannot beat the best real value found, so the raycasts
     * spent stay a handful. (The other way to close the gap, reading the ring at exposure 1.0 beyond one block,
     * would throw away the cover B2 measures, the very shortfall it fixed, for every moving player.)
     */
    public static List<Offset> reachPoints(double vx, double vz, long landingTicks) {
        List<Offset> base = new ArrayList<>(offsets(vx, vz, landingTicks));
        base.addAll(arcMidpoints(vx, vz, landingTicks));
        return withMidpoints(base);
    }

    /**
     * {@code reach} plus, for every offset, the halfway points towards it: half the way horizontally, half the
     * way vertically, and both (task B2 fix round 1). At exposure 1.0 the reach points' own raw damage bounded
     * everything between them; at real exposure it does not, since cover at two points can leave open ground
     * between them (a pillar, an edge), at nearly the same distance. The halfway points are that ground: to a
     * jump's height the half is about {@code JUMP_HEIGHT / 2} and to a ring point's radius half the radius. They
     * are ordinary ranked points (their own exposure-1.0 raw is their ceiling), so the search still prunes them.
     * Never throws; a non-finite offset stays non-finite, as everywhere in this class.
     */
    public static List<Offset> withMidpoints(List<Offset> reach) {
        List<Offset> all = new ArrayList<>(reach.size() * 4);
        for (Offset o : reach) {
            all.add(o);
            all.add(new Offset(o.dx() / 2, o.dy(), o.dz() / 2));
            all.add(new Offset(o.dx(), o.dy() / 2, o.dz()));
            all.add(new Offset(o.dx() / 2, o.dy() / 2, o.dz() / 2));
        }
        return List.copyOf(all);
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

    /** One reach point with its distance to the explosion and its raw damage at exposure 1.0 (the ceiling). */
    public record Ranked(Offset offset, double distance, float rawAtFullExposure) {
    }

    /** The exposure (0 to 1, vanilla's definition) an explosion has on you standing at a reach offset. */
    @FunctionalInterface
    public interface ExposureFunction {
        double at(Offset offset);
    }

    /**
     * The distinct reach points, worst first: sorted by their raw damage at exposure 1.0, the most any explosion
     * could deal there, so that the first one is the ceiling of all the others. A NaN raw (a non-finite
     * explosion offset, see {@link #worstRawDamage}) sorts first. Never throws, same input contract as
     * {@link #offsets}.
     */
    public static List<Ranked> rankedByWorstRaw(double ex, double ey, double ez, double vx, double vz,
                                                long landingTicks) {
        return rankedByWorstRaw(ex, ey, ez, offsets(vx, vz, landingTicks));
    }

    /** {@link #rankedByWorstRaw(double, double, double, double, double, long)} over the given reach offsets. */
    public static List<Ranked> rankedByWorstRaw(double ex, double ey, double ez, List<Offset> reach) {
        Set<Offset> distinct = new LinkedHashSet<>(reach);
        List<Ranked> ranked = new ArrayList<>(distinct.size());
        for (Offset o : distinct) {
            double dx = ex - o.dx();
            double dy = ey - o.dy();
            double dz = ez - o.dz();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            ranked.add(new Ranked(o, distance, ExplosionMath.rawDamage(distance, 1.0)));
        }
        ranked.sort(Comparator.comparingDouble((Ranked r) -> r.rawAtFullExposure()).reversed());
        return ranked;
    }

    /**
     * The exact worst raw damage over the reach points with their real exposure: a branch and bound over
     * {@code ranked} (worst first). Each point's real raw can never exceed its exposure-1.0 raw, so once the
     * next point's ceiling is not above the best real value found (starting from {@code floor}, the value
     * already known for where you stand now), no later point can beat it and the search stops. Never below
     * {@code floor}; never below any point's real value; a NaN ceiling or a NaN result propagates as NaN, like
     * {@link #worstRawDamage}. An {@code exposure} that is not a finite number in 0..1 is read as 1.0, the
     * cautious value, and so is any point the caller could not afford to measure.
     */
    public static float worstRawDamage(List<Ranked> ranked, ExposureFunction exposure, float floor) {
        float best = floor;
        for (Ranked r : ranked) {
            if (Float.isNaN(r.rawAtFullExposure())) return Float.NaN;
            if (r.rawAtFullExposure() <= best) break;
            double e = exposure.at(r.offset());
            if (!(e >= 0.0 && e <= 1.0)) e = 1.0;
            best = Math.max(best, ExplosionMath.rawDamage(r.distance(), e));
        }
        return best;
    }
}
