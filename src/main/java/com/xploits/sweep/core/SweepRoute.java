package com.xploits.sweep.core;

import com.xploits.travel.core.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole trip that flying a sweep plan involves: the vertices in the order they are flown and
 * <b>every</b> distance that has to be budgeted, from where the player is until they come back.
 *
 * <p><b>This class exists because of a bug that has already slipped into this module twice</b>, and
 * that is why the arithmetic lives here and not in the adapter.
 * {@link SweepPlanner.SweepPlan#totalBlocks()} measures the sweep —from the start of the first lane
 * to the end of the last, links included— and <b>not</b> the trip: it lacks the <b>approach</b>, the
 * stretch from wherever the player is to the start of the first lane, which in a sweep of the kind
 * that justifies this module is the longest leg of all. Estimating fireworks with the plan's "total"
 * gives too few fireworks, and then the module says "you have enough" to a player who does not: the
 * advance check, which is exactly the one that exists to prevent the trip, passes falsely.
 *
 * <p>A {@code SweepPlan} cannot fix it because it is geometry of the area and does not know where the
 * player is. {@code SweepRoute} does know —it is built with their position— and that is why it is the
 * place where the complete trip can be computed once, with tests, instead of being redone by hand in
 * the adapter every time it is needed.
 *
 * <p>This is where the number feeding the firework projection of {@link FuelBudget#willRunOut} comes
 * from, which is the one that decides whether the flight is cut: {@link #remainingFrom(int, Waypoint)}
 * is exactly the {@code blocksRemaining} that class asks for, with the return inside if the budget
 * includes it.
 *
 * <p>This class does not touch Minecraft or Meteor: it works on {@link Lane} and {@link Waypoint} as
 * numbers.
 */
public final class SweepRoute {
    private final List<Waypoint> waypoints;

    /**
     * How many blocks are left from vertex {@code i} to the end of the trip, return included if it
     * counts. It is precomputed from back to front only once, so that asking for it in flight costs a
     * read and not a walk of the whole route.
     */
    private final double[] remainingFrom;

    private final double approach;
    private final double returnTrip;
    private final double shortestLane;
    private final double shortestLink;

    private SweepRoute(List<Waypoint> waypoints, double[] remainingFrom, double approach,
                       double returnTrip, double shortestLane, double shortestLink) {
        this.waypoints = waypoints;
        this.remainingFrom = remainingFrom;
        this.approach = approach;
        this.returnTrip = returnTrip;
        this.shortestLane = shortestLane;
        this.shortestLink = shortestLink;
    }

    /**
     * Builds the trip from the plan's lanes and where the player is.
     *
     * <p>Every lane contributes two vertices, its start and its end, in that order. The lanes already
     * come out of {@link SweepPlanner} alternating direction, so each one starts where the previous
     * one ended and the link between two is the hop between bands; nothing is reordered here.
     *
     * @param lanes       the lanes to fly, in the order they come out of the plan
     * @param origin      where the player is at launch, which is where the approach starts from and
     *                    where the return goes back to
     * @param countReturn whether the budget includes flying back to the starting point
     * @throws IllegalArgumentException if there is no lane at all: a plan without lanes is not a
     *                                  short route, it means there is nothing to fly —the area is
     *                                  already fully seen—, and building an empty route would let
     *                                  someone take off towards nowhere
     * @throws NullPointerException     if {@code lanes} or {@code origin} are null
     */
    public static SweepRoute of(List<Lane> lanes, Waypoint origin, boolean countReturn) {
        if (lanes == null) throw new NullPointerException("the route needs the plan's lanes");
        if (origin == null) throw new NullPointerException("the route needs to know where it takes off from");
        if (lanes.isEmpty()) {
            throw new IllegalArgumentException(
                "a plan without lanes produces no route: it means the area is already fully"
                    + " seen and there is nothing to fly, not that the trip is short");
        }

        List<Waypoint> vertices = new ArrayList<>(lanes.size() * 2);
        for (Lane lane : lanes) {
            vertices.add(new Waypoint(lane.fromX(), lane.fromZ()));
            vertices.add(new Waypoint(lane.toX(), lane.toZ()));
        }

        double returnTrip = countReturn ? vertices.get(vertices.size() - 1).distanceTo(origin) : 0;

        double[] remaining = new double[vertices.size()];
        remaining[vertices.size() - 1] = returnTrip;
        for (int i = vertices.size() - 2; i >= 0; i--) {
            remaining[i] = vertices.get(i).distanceTo(vertices.get(i + 1)) + remaining[i + 1];
        }

        // The vertices come in pairs -start and end of each lane-, so the gap between 2i and 2i+1 is a
        // lane and the one from 2i+1 to 2i+2 is the link to the next. The two minimums are kept
        // apart because they mean different things: see their methods.
        double shortestLane = Double.MAX_VALUE;
        double shortestLink = Double.MAX_VALUE;
        for (int i = 0; i + 1 < vertices.size(); i++) {
            double gap = vertices.get(i).distanceTo(vertices.get(i + 1));
            if (i % 2 == 0) shortestLane = Math.min(shortestLane, gap);
            else shortestLink = Math.min(shortestLink, gap);
        }

        double approach = origin.distanceTo(vertices.get(0));
        return new SweepRoute(List.copyOf(vertices), remaining, approach, returnTrip, shortestLane,
            shortestLink);
    }

    /** The vertices in the order they are flown: start and end of each lane. */
    public List<Waypoint> waypoints() {
        return waypoints;
    }

    /** How many vertices the route has, which is twice the number of lanes. */
    public int size() {
        return waypoints.size();
    }

    /**
     * The approach: from where the player was at launch to the start of the first lane. It is the
     * leg {@link SweepPlanner.SweepPlan#totalBlocks()} lacks and the longest of all in a sweep far
     * from home.
     */
    public double approachBlocks() {
        return approach;
    }

    /**
     * The sweep itself: the lanes plus the links joining them. <b>It has to be worth exactly the
     * same</b> as {@link SweepPlanner.SweepPlan#totalBlocks()} of the plan this route came from, and
     * there is a test that checks it against the real planner: they are two different paths to the
     * same number —that one adds lane lengths and hops, this one adds distances between consecutive
     * vertices— and if they ever stopped matching it would mean one of the two had broken.
     */
    public double sweepBlocks() {
        return remainingFrom[0] - returnTrip;
    }

    /** The way back from the end of the last lane to the starting point, or zero if it does not count. */
    public double returnBlocks() {
        return returnTrip;
    }

    /**
     * The whole trip: approach, sweep and return. <b>This</b> is the number the fuel is estimated
     * with before takeoff, and not the plan's.
     */
    public double totalBlocks() {
        return approach + sweepBlocks() + returnTrip;
    }

    /**
     * What is left to fly from vertex {@code index} to the end of the trip, return included if it
     * counts. It does not include where the player is right now: that is what
     * {@link #remainingFrom(int, Waypoint)} is for.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not a vertex of this route
     */
    public double remainingFrom(int index) {
        if (index < 0 || index >= remainingFrom.length) {
            throw new IndexOutOfBoundsException(
                "vertex " + index + " does not exist in a route of " + remainingFrom.length
                    + " vertices");
        }
        return remainingFrom[index];
    }

    /**
     * What the player still has to fly: from where they are to the vertex they are heading for, plus
     * the rest of the route, plus the return if it counts.
     *
     * <p>It is exactly the {@code blocksRemaining} that {@link FuelBudget#willRunOut} asks for, and the
     * reason why that sum is not done in the adapter: the decision to cut the flight comes from it,
     * and too short a distance makes the projection say the fireworks will last when they will not.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not a vertex of this route
     * @throws NullPointerException      if {@code player} is null
     */
    public double remainingFrom(int index, Waypoint player) {
        if (player == null) throw new NullPointerException("where the player is must be known");
        return player.distanceTo(waypoints.get(index)) + remainingFrom(index);
    }

    /**
     * The minimum spacing that can be required of two consecutive vertices of a <b>sweep</b> with the
     * configured waypoint margin: the margin, plain and simple.
     *
     * <p><b>It is not {@code RoutePlanner.minimumSpacing}, and that difference is the fix.</b> That
     * number is twice the margin and moreover never drops below 300 blocks, and both terms come from
     * a problem that does not arise here:
     *
     * <ul>
     *   <li><b>Twice the margin comes from two aligned vertices.</b> In an evasion route the next
     *       waypoint can be right behind the current one and in the same direction: on dropping the
     *       first one you are already one margin away from it, and the second falls inside the other
     *       margin. In a sweep that cannot happen, because <b>the vertices form a right angle</b>: the
     *       one that ends a lane is reached <i>along</i> the lane, and the one that starts the next
     *       is <i>perpendicular</i>, one band away. If the first is dropped while at
     *       {@code d ≤ margin} from it, the distance to the second is {@code hypotenuse(d, gap)},
     *       which never drops below the gap. It is enough, then, for the gap to exceed the
     *       margin.</li>
     *   <li><b>The 300 blocks come from elytra physics</b>: below a few turning radii, Baritone
     *       overshoots and comes back for the vertex. That makes the flight uglier, but <b>it loses
     *       no lane</b> —the vertex is still the target and ends up being reached—, whereas the lie
     *       of spec §9 is only produced by a vertex consumed without having been flown. A floor that
     *       does not protect against that cannot be the one that decides whether the sweep is
     *       rejected; whoever wants to say it can warn about it.</li>
     * </ul>
     *
     * <p><b>What importing it cost:</b> the shortest gap of a sweep is the link between lanes,
     * {@code width × 16} blocks. With the factory margin, a floor of 300 required width ≥ 19 chunks,
     * that is an observed radius ≥ 12; a server that declared 8, 10 or 11 —normal on anarchy— saw
     * <b>every measured sweep rejected, always, for any rectangle</b>, and none of the ways out the
     * rejection offered worked there: below 150 the margin did not move the floor, enlarging the
     * area does not separate the bands, and raising the width by hand does not apply to whoever has
     * it measured. With this rule, that same radius of 8 gives width 12 and gap 192, which passes
     * with room to spare, and the margin is a real way out again: at its minimum it accepts down to
     * an observed radius of 5.
     *
     * @param waypointMargin how many blocks before each intermediate vertex Baritone's target is
     *                       changed
     */
    public static double minimumGap(double waypointMargin) {
        return waypointMargin;
    }

    /**
     * The shortest lane of the plan, in blocks: the gap between the vertex that starts it and the one
     * that ends it.
     *
     * <p><b>This is the number that decides whether a sweep can be flown</b>, because it is the only
     * gap whose loss is the lie of spec §9. If a lane fits inside the waypoint margin, its two
     * vertices are consumed almost back to back and <b>the lane is never flown</b>: the adapter goes
     * from aiming at it to taking it as done, the sweep counts it as its own and that strip of the
     * rectangle is marked as combed without anyone having looked at it.
     *
     * <p>Compare with {@link #minimumGap(double)}. It can only fall short in an area that is tiny
     * along its long axis: the lanes run from end to end, so the shortest one measures the long side
     * of the whole rectangle.
     */
    public double shortestLane() {
        return shortestLane;
    }

    /**
     * The shortest link between two consecutive lanes, in blocks: the perpendicular hop from one band
     * to the next.
     *
     * <p><b>This one falling short loses no lane</b>, and that is why it is a reason to warn and not
     * to reject. If the link fits inside the margin, the adapter drops the vertex that ends one lane
     * and on the next tick also drops the one that starts the other, so Baritone never receives the
     * corner: its target becomes <b>the end of the next lane</b>, and it flies there diagonally. That
     * diagonal covers the whole long axis while drifting one band across, that is it crosses the
     * band of the lost lane anyway —what is lost is the clean corner, not the terrain—. What the
     * sweep can never do is skip a whole lane, and that is watched by {@link #shortestLane()}.
     *
     * <p>And it cannot chain: once the link is consumed, the next vertex is the end of the lane,
     * which is a whole lane away. At most one corner per turn is lost.
     *
     * <p>It is usually the shortest gap of the route, and it usually measures the lane width times
     * 16. The minimum shows up in the last band when the area is not an exact multiple of the width:
     * that band comes out narrower, its lane is centred closer to the previous one, and the link gets
     * to be worth little more than half a width.
     */
    public double shortestLink() {
        return shortestLink;
    }

    /**
     * The shortest spacing between two consecutive vertices of the route, whatever their kind.
     *
     * <p><b>It is not the number that decides whether a sweep is flown</b>, and mistaking it for that
     * cost a whole round: the two kinds of gap weigh different things. If the one that falls short is
     * a lane, that lane is not flown and the sweep counts it as combed anyway —the lie of spec §9,
     * and that is rejected—; if it is a link between lanes, what is lost is the corner and not the
     * terrain, and that is warned about. To decide, {@link #shortestLane()} and
     * {@link #shortestLink()}; this is the general query, useful to describe the route or to compare
     * it with the elytra's physical floor.
     *
     * <p>A single-lane route has one single gap, that of the lane itself.
     */
    public double tightestGap() {
        double minimum = Double.MAX_VALUE;
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            minimum = Math.min(minimum, waypoints.get(i).distanceTo(waypoints.get(i + 1)));
        }
        return minimum;
    }
}
