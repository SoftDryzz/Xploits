package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out a trip's route: the sequence of waypoints that lead from {@code origin} to the
 * destination following the chosen decoy pattern (AutoTravel spec).
 *
 * <p>All the geometry lives here, in Minecraft blocks on the XZ plane, knowing nothing about Baritone
 * or the real world: that is the business of {@link BaritoneScript} and the adapter.
 *
 * <p>No pattern takes the player out of a highway's corridor (spec §4.2): in ZIGZAG and SWERVE the
 * amplitude is capped, in SPIRAL the radius is capped, and DECOY -which would lose its reason to exist
 * if it were capped, a decoy that cannot point off the axis is nothing- is rejected instead of
 * downgraded. What still works when capped is capped; what does not is rejected.
 *
 * <p>The other side of that same doctrine: a cap that leaves the pattern without a pattern is no
 * longer a cap. If the step does not fit even once in the trip, if it is zero or negative, or if the
 * amplitude -or the radius, or the turns, or the decoy's angle- leaves the waypoints on the axis, the
 * route comes out straight even though evasion was asked for. That is rejected with a reason instead
 * of being delivered silently: the player who believes they are weaving and flies straight draws
 * exactly the line that gives away their base. And the reason always states the concrete numbers and
 * the way out, because a rejection that does not say how to get out is almost as bad as silence.
 *
 * <p>The waypoint cap follows the same rule. It caps the COUNT of points, not the pattern's reach:
 * trimming the count would weave the start of the trip and leave the end -the stretch that arrives
 * home- in a straight line, which is the worst half to leave it in. So a pattern that does not fit
 * whole under the cap is rejected, and its step is not stretched to make it fit: a zigzag with period
 * 200 when 100 was asked for does cover the journey, yes, but it is not the pattern that was asked
 * for, and keeping quiet about it is the same silent downgrade in another disguise.
 *
 * <p><b>And the geometry has to be flyable, not just drawable.</b> Baritone considers the route done
 * and starts looking for a place to land as soon as the player comes within 48 blocks of its goal
 * ({@link #BARITONE_LANDING_DISTANCE}), so the adapter changes its goal well before arriving. That
 * requires two consecutive waypoints to be far enough apart for that head start to fit between them:
 * otherwise they are consumed in a burst without being flown and the pattern downgrades itself. The
 * minimum spacing lives here ({@link #minimumSpacing(double)}) and applies to all four patterns.
 *
 * <p>The two possible remedies are not worth the same, and that is why the same one is not applied
 * to all:
 *
 * <ul>
 *   <li><b>ZIGZAG and SWERVE are periodic.</b> Removing intermediate points from them is stretching
 *       their step behind their back -a zigzag with period 6000 when 2000 was asked for-, exactly the
 *       substitution the previous paragraph rejects. So if their step and amplitude leave the
 *       waypoints too close together <b>it is rejected</b>, with the number to raise them to.</li>
 *   <li><b>SPIRAL is the sampling of a continuous curve</b> that closes in on the destination, and
 *       its last stretch is <b>invariably</b> unflyable: by definition it ends at radius zero, and no
 *       setting fixes it. There it is trimmed -the core's steps stop being emitted, which is the same
 *       cap the highway corridor already applies to the radius-, and the curve that is left is the
 *       same curve, not another. Only if not a single waypoint off the axis survives that trim is it
 *       rejected: then the route would be straight.</li>
 * </ul>
 */
public final class RoutePlanner {
    /**
     * Cap on the waypoints a ZIGZAG or SWERVE pattern can generate. It guards against degenerate
     * parameters (a 1-block step on a 100 000-block trip, for instance) that would generate hundreds
     * of thousands of points: the adapter would have to send them one by one to Baritone's chat, so
     * such a large number is in practice a client hang, not a usable route. 500 waypoints are already
     * far more than any reasonable configuration produces (with the default values, a 100 000-block
     * trip generates 50).
     *
     * <p>This cap does NOT trim the route: asking for more than 500 side changes is rejected in
     * {@link #lateralRejection}. Truncating the count would leave the pattern half done and the rest
     * of the trip straight, which is precisely the silent downgrade this class does not deliver.
     */
    public static final int MAX_PATTERN_WAYPOINTS = 500;

    /**
     * Spiral steps per full turn, set so that the default setting ({@code spiralTurns = 1.5}) still
     * gives exactly 36 steps. The steps scale with the configured turns -instead of staying fixed at
     * 36- because otherwise more turns mean more degrees per step: with 3 turns and a fixed 36 steps
     * the angular step reaches 30°, and the "spiral" looks like a star polygon instead of a curve.
     */
    private static final double SPIRAL_STEPS_PER_TURN = 24.0;
    /** A minimum number of steps so that a few turns do not degenerate into a triangle. */
    private static final int MIN_SPIRAL_STEPS = 8;

    /** Below this distance, origin and destination are considered the same point. */
    private static final double SAME_POINT_TOLERANCE = 1e-9;

    /** Below this distance from the axis, a waypoint is considered placed on the line. */
    private static final double AXIS_TOLERANCE = 1e-6;

    /**
     * How many blocks from its goal Baritone considers the route done and <b>starts landing</b>.
     *
     * <p>Read from the bytecode of the installed jar ({@code baritone-standalone-fabric-1.17.0.jar},
     * class {@code baritone/ju.class}, with {@code javap -p -c}): right before the {@code ldc} of the
     * string {@code "Path complete, searching for safe landing spot..."} it compares the player's
     * <b>squared</b> distance to the route's destination against the constant {@code 2304.0d}, and
     * {@code sqrt(2304) = 48}.
     *
     * <p><b>No Baritone setting turns it off.</b> Its 29 {@code elytra*} settings were reviewed:
     * {@code elytraAllowEmergencyLand} and {@code elytraMinFireworksBeforeLanding} are for running out
     * of fireworks, not for this. {@code #elytra} means "fly to the goal <b>and land</b>", and that is
     * not negotiable; the only thing that can be done is take the goal away before it arrives.
     */
    public static final double BARITONE_LANDING_DISTANCE = 48;

    /**
     * How many blocks before an intermediate waypoint the adapter changes Baritone's goal.
     *
     * <p>Where the number comes from, adding up what has to fit inside:
     *
     * <ul>
     *   <li><b>48 blocks</b> of {@link #BARITONE_LANDING_DISTANCE}: below that Baritone is already
     *       landing and the damage is done.</li>
     *   <li><b>~2 blocks</b> of granularity: the adapter looks at the distance once per tick (20 Hz)
     *       and a rocket-boosted elytra goes at about 33 blocks per second, so between crossing the
     *       margin and noticing, a little under one tick of flight can pass.</li>
     *   <li><b>~70 blocks</b> of reaction: between the {@code goal} and the {@code elytra} going out
     *       through the chat and Baritone stopping chasing the old goal there is a route recalculation
     *       that is not instant and cannot be measured from here. Two seconds of flight are budgeted,
     *       twice what it is seen to take in the log, because falling short reintroduces the whole
     *       failure and overshooting only rounds the corners a bit more.</li>
     * </ul>
     *
     * <p>48 + 2 + 70 = 120, rounded to <b>150</b> to leave a good second of slack. Below {@link
     * #MIN_WAYPOINT_MARGIN} the reaction budget shrinks to nothing and the landing comes back, so that
     * is where the setting's floor is.
     *
     * <p><b>What it costs:</b> changing goal 150 blocks early rounds the pattern's corners, because the
     * player never gets to touch the vertex. How much it rounds depends on how flat the leg comes in:
     * with the default ZIGZAG (period 2000, amplitude 200) the leg is almost parallel to the axis and
     * the vertex ends up about 29 blocks short of its amplitude -15 %-; with the default SWERVE (leg
     * 5000, offset 800), about 46 of 800 -6 %-. It is the price of not landing 73 times, and it is
     * cheap.
     */
    public static final double DEFAULT_WAYPOINT_MARGIN = 150;

    /**
     * The lowest margin allowed. With 48 blocks of landing and a tick of granularity, 100 leaves about
     * 50 blocks -a second and a half- for Baritone to let go of the old goal. Less than that is no
     * longer a margin, it is a bet.
     *
     * <p>The default margin before this fix was <b>30 blocks</b>, below Baritone's 48: the goal was
     * changed when it had already been landing for a while. This floor also fixes whoever has that 30
     * saved in their configuration: Meteor's {@code Setting.set} returns {@code false} without writing
     * anything when the value does not pass {@code isValueValid}, and {@code DoubleSetting.load} loads
     * through there, so a persisted 30 is discarded on load and the setting stays at its default
     * value.
     */
    public static final double MIN_WAYPOINT_MARGIN = 100;

    /**
     * The physical floor of the spacing between two consecutive waypoints, in blocks, regardless of
     * the configured margin.
     *
     * <p>Where it comes from: a rocket-boosted elytra goes at about 33 blocks per second and does not
     * turn sharply -to turn around it needs a couple of seconds and a radius in the order of 70 or 80
     * blocks-. Two waypoints less than a few turning radii apart are not two legs: they are a wobble,
     * and Baritone overshoots them and comes back for them. 300 blocks are about 9 seconds of cruising
     * and about four turning radii: the shortest leg that still flies as a leg.
     *
     * <p>It matches twice the default margin, and that is no coincidence: they are the two floors of
     * the same problem reached by different paths -one the elytra's physics, the other Baritone's
     * bookkeeping- and they have been squared on purpose so that the default setting does not depend
     * on which of the two rules.
     */
    public static final double MIN_WAYPOINT_SPACING = 300;

    /**
     * How many blocks from the <b>last</b> waypoint the trip counts as arrived.
     *
     * <p>It is not a setting, and that is on purpose: the last waypoint is the real destination and
     * landing there is exactly what is wanted, so there is nothing to bring forward and no number to
     * tune. The 30 blocks are what the module has always used. The number that does depend on the
     * player -how early to let go of intermediate waypoints- is {@code waypoint-margin}, and two
     * similar sliders with such different meanings only invite touching the wrong one.
     */
    public static final double ARRIVAL_MARGIN = 30;

    private RoutePlanner() {
    }

    /**
     * What the player has left to fly: from where they are to the waypoint they are heading to, plus
     * the rest of the route. It is the sum of {@code SweepRoute.remainingFrom}, for the console header.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not a waypoint of the route
     * @throws NullPointerException      if {@code here} is null
     */
    public static double remainingBlocks(List<Waypoint> route, int index, Waypoint here) {
        if (here == null) throw new NullPointerException("the player's position is needed");
        if (index < 0 || index >= route.size()) {
            throw new IndexOutOfBoundsException("waypoint " + index + " does not exist in a route of " + route.size());
        }
        double total = here.distanceTo(route.get(index));
        for (int i = index; i < route.size() - 1; i++) total += route.get(i).distanceTo(route.get(i + 1));
        return total;
    }

    /**
     * At what distance from waypoint {@code index} of a route of {@code waypointCount} points it
     * counts as reached. <b>It is not the same number for all of them</b>, and that is the fix.
     *
     * <p>{@code #elytra} means "fly to the goal <b>and land</b>": Baritone considers the route done and
     * starts looking for a place to land as soon as it comes within {@link #BARITONE_LANDING_DISTANCE}
     * blocks of its goal, and it has no setting to turn that off. With the 30-block margin from before
     * this fix, the goal change arrived <b>after</b> it had started coming down: a 74-waypoint pattern
     * meant 73 landings and 73 take-offs spread over the journey -and with {@code elytraAutoJump}, 73
     * jumps from the ground- in a module whose reason to exist is to leave no trail. So intermediate
     * waypoints get their goal changed {@code waypointMargin} blocks before arriving.
     *
     * <p>Not the <b>last</b> one: that is the real destination, the only place where landing is what
     * was asked for. Getting ahead by 150 blocks there would mean sending Baritone a {@code cancel} in
     * the middle of the descent and dropping the player in mid-air, so {@link #ARRIVAL_MARGIN} is used.
     */
    public static double reachedMargin(int index, int waypointCount, double waypointMargin) {
        return index == waypointCount - 1 ? ARRIVAL_MARGIN : waypointMargin;
    }

    /**
     * The minimum spacing required of two consecutive waypoints with the configured margin {@code
     * waypointMargin}.
     *
     * <p><b>Twice</b> the margin, and not the plain margin, because the margin is spent twice: when
     * the adapter lets go of waypoint N it is already {@code margin} blocks from it, and if waypoint
     * N+1 is not more than another {@code margin} ahead, the same tick that lets go of N also lets go
     * of N+1. The points would be consumed in a burst without being flown -a burst of {@code goal} to
     * the chat and a pattern that trims itself-, which is the usual silent downgrade in another
     * disguise.
     *
     * <p>And never below {@link #MIN_WAYPOINT_SPACING}, which is what the elytra can fly as a leg even
     * if the margin drops.
     */
    public static double minimumSpacing(double waypointMargin) {
        return Math.max(MIN_WAYPOINT_SPACING, 2 * waypointMargin);
    }

    /**
     * Plans the trip from {@code origin} to {@code destination} with the pattern {@code pattern}.
     *
     * @param highwayMaxAmplitude the maximum corridor width allowed when the destination is a highway
     *                            one; outside a highway it is not used
     * @param waypointMargin      how many blocks before each intermediate waypoint the adapter changes
     *                            Baritone's goal; the minimum spacing required of the geometry comes
     *                            from it ({@link #minimumSpacing(double)})
     * @return the route, or a rejection if the pattern is not compatible with the requested destination
     */
    public static Route plan(Waypoint origin, Destination destination, FlightPattern pattern,
                              PatternParams params, double highwayMaxAmplitude, double waypointMargin) {
        if (pattern == FlightPattern.DECOY && destination.highway()) {
            return Route.rejected(Msg.of(TravelText.DECOY_ON_HIGHWAY));
        }

        Waypoint destinationPoint = destination.resolve(origin);
        double distance = origin.distanceTo(destinationPoint);
        if (distance < SAME_POINT_TOLERANCE) {
            return Route.of(List.of(destinationPoint));
        }

        double ux = (destinationPoint.x() - origin.x()) / distance;
        double uz = (destinationPoint.z() - origin.z()) / distance;
        double nx = -uz;
        double nz = ux;
        double spacing = minimumSpacing(waypointMargin);

        return switch (pattern) {
            case STRAIGHT -> Route.of(List.of(destinationPoint));
            case ZIGZAG -> {
                double amplitude = effectiveAmplitude(params.amplitude(), destination, highwayMaxAmplitude);
                Msg rejection = lateralRejection(pattern, distance, params.period(),
                    params.amplitude(), amplitude, destination.highway(), spacing, waypointMargin);
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.period(),
                        amplitude, spacing));
            }
            case SWERVE -> {
                double amplitude = effectiveAmplitude(params.lateralOffset(), destination, highwayMaxAmplitude);
                Msg rejection = lateralRejection(pattern, distance, params.legLength(),
                    params.lateralOffset(), amplitude, destination.highway(), spacing, waypointMargin);
                yield rejection != null ? Route.rejected(rejection)
                    : Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.legLength(),
                        amplitude, spacing));
            }
            case SPIRAL -> {
                double radiusCap = destination.highway() ? highwayMaxAmplitude : Double.POSITIVE_INFINITY;
                double radius = Math.min(Math.min(params.spiralRadius(), distance / 2.0), radiusCap);
                Msg rejection = spiralRejection(params.spiralRadius(), radius, params.spiralTurns(),
                    destination.highway());
                if (rejection != null) yield Route.rejected(rejection);

                List<Waypoint> points = spaceOut(origin,
                    spiral(destinationPoint, ux, uz, nx, nz, radius, params), spacing);
                yield leavesTheAxis(points, origin, nx, nz)
                    ? Route.of(points)
                    : Route.rejected(spiralSpacingRejection(origin, destinationPoint, ux, uz, nx, nz, distance,
                        params, radius, destination.highway(), highwayMaxAmplitude, spacing, waypointMargin));
            }
            case DECOY -> {
                Msg rejection = decoyRejection(params.decoyAngleDegrees(), params.decoyFraction());
                if (rejection != null) yield Route.rejected(rejection);

                List<Waypoint> points = decoy(origin, destinationPoint, ux, uz, distance, params);
                double shortest = Math.min(origin.distanceTo(points.get(0)),
                    points.get(0).distanceTo(destinationPoint));
                yield shortest >= spacing
                    ? Route.of(points)
                    : Route.rejected(decoySpacingRejection(distance, params, shortest, spacing, waypointMargin));
            }
        };
    }

    /** Highway mode caps the amplitude: outside a highway the configured one is used untouched. */
    private static double effectiveAmplitude(double amplitude, Destination destination, double highwayMaxAmplitude) {
        return destination.highway() ? Math.min(amplitude, highwayMaxAmplitude) : amplitude;
    }

    /**
     * The four ways in which a lateral pattern -ZIGZAG and SWERVE are the same family, spec §5- ends
     * up without a pattern, all four without making a sound (and a fifth, the one below, in which the
     * pattern comes out whole but cannot be flown):
     *
     * <ul>
     *   <li><b>The step is zero or negative.</b> There is no progress between side changes, so there
     *       is no weave to draw. This used to be silently downgraded to STRAIGHT so as not to blow up
     *       into billions of iterations -{@code (int) floor(distance/0.0)} is {@code
     *       Integer.MAX_VALUE}-, but a rejection avoids the hang just as well and is also heard: it
     *       returns before entering any loop. A zero step does not "work when capped", it does not
     *       work.</li>
     *   <li><b>Not a single leg fits.</b> {@code floor(distance/step)} is 0 as soon as the distance is
     *       less than the step, so not a single pattern point is generated. With SWERVE's default leg
     *       (5000) that is every trip under 5000 blocks.</li>
     *   <li><b>The effective amplitude is zero.</b> The points are generated, but all collinear with
     *       the axis: a straight line with decorative waypoints. No absurd parameter is needed to get
     *       here, a highway destination with the corridor width at 0 is enough.</li>
     *   <li><b>The pattern does not fit whole under the waypoint cap.</b> {@code floor(distance/step)}
     *       exceeds {@link #MAX_PATTERN_WAYPOINTS}. This is the only one of the four in which the route
     *       does not come out entirely straight: THE END comes out straight, which is worse. The
     *       slider's minimum period (100) with a destination 100 000 blocks away asks for 1000 side
     *       changes; truncating the count to 500 weaves the first 50 000 blocks and leaves the other
     *       50 000 in a perfect line pointing at the base, precisely the stretch that arrives home.
     *       Spec §5 promises the opposite ("the pattern applies over the whole journey"), so the
     *       player believes the whole trip weaves.</li>
     * </ul>
     *
     * <p>In all four cases the route comes out straight -whole or in its final stretch- even though
     * evasion was asked for, so it is rejected with a reason, same as the decoy on a highway. An
     * amplitude capped to zero no longer "works when capped".
     *
     * <p>And a fifth, which is not that the pattern is not drawn but that it cannot be flown: <b>the
     * waypoints end up too close together</b>. Two consecutive points of a lateral pattern are {@code
     * hypot(step, 2*amplitude)} apart, and the first is {@code hypot(step, amplitude)} from the origin
     * -the shorter of the two, so it is the one that rules-. If that gap does not reach {@link
     * #minimumSpacing(double)}, the adapter lets go of one waypoint and the next in the same tick and
     * the pattern is consumed in a burst. Here points are <b>not</b> removed to fix it: in a periodic
     * pattern removing one in two is stretching the step behind the player's back, the same silent
     * substitution of the paragraph below. It is rejected, and the reason carries the two numbers to
     * raise -step or amplitude- so that the player chooses which one to move.
     *
     * <p>It goes last of the five on purpose: the four above say "there would be no pattern" and this
     * one says "there would be a pattern but the elytra cannot fly it". A 100-block period on a 100 000
     * trip breaks both, and the useful reason is the cap's, which names the stretch that would stay
     * straight.
     *
     * <p>The fourth has a tempting way out that is NOT taken: stretching the step to {@code
     * distance/500} would cover the whole trip while respecting the cap, rejecting nothing and letting
     * the player fly. But that is handing them a pattern they did not ask for -a zigzag with period 200
     * when they set 100- and keeping quiet about it, which is the same silent downgrade in another
     * disguise: the shape is changed instead of the length, and the player still believes they are
     * flying what they configured. Capping the step is not capping: it is replacing it. Between
     * misleading them and stopping them with a reason that says exactly how far to raise the step,
     * they are stopped; the number they need goes in the reason and they raise it themselves, knowing
     * what they fly.
     *
     * @return the rejection's reason, or {@code null} if the pattern can really be drawn
     */
    private static Msg lateralRejection(FlightPattern pattern, double distance, double step,
                                            double configuredAmplitude, double effectiveAmplitude,
                                            boolean highway, double minSpacing, double waypointMargin) {
        if (step <= 0) {
            return Msg.of(TravelText.LATERAL_NO_STEP, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step);
        }
        if (distance < step) {
            return Msg.of(TravelText.LATERAL_STEP_OVER_TRIP, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "distance", distance);
        }
        if (effectiveAmplitude <= 0) {
            return Msg.of(TravelText.LATERAL_NO_SIDE, "pattern", pattern.name(), "side", effectiveSideName(pattern),
                "amplitude", effectiveAmplitude, "fix", howToWiden(sideName(pattern), configuredAmplitude, highway));
        }
        double neededSteps = Math.floor(distance / step);
        if (neededSteps > MAX_PATTERN_WAYPOINTS) {
            // The minimum step that covers the whole trip, rounded up so that it is a round number of
            // blocks and so that floor(distance/step) lands on the cap or below, never just above it
            // by a decimal.
            double minimumStep = Math.ceil(distance / MAX_PATTERN_WAYPOINTS);
            double covered = MAX_PATTERN_WAYPOINTS * step;
            return Msg.of(TravelText.LATERAL_TOO_MANY_SIDES, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "needed", neededSteps, "distance", distance, "max", MAX_PATTERN_WAYPOINTS,
                "covered", covered, "rest", distance - covered, "minimum", minimumStep);
        }
        double firstGap = Math.hypot(step, effectiveAmplitude);
        if (firstGap < minSpacing) {
            // What to raise to reach the spacing, keeping the other value. Both radicands are
            // positive precisely because we got in here: hypot(step, amplitude) < spacing implies
            // that the step and the amplitude are both smaller than the spacing.
            double neededStep = Math.ceil(Math.sqrt(minSpacing * minSpacing - effectiveAmplitude * effectiveAmplitude));
            double neededAmplitude = Math.ceil(Math.sqrt(minSpacing * minSpacing - step * step));
            return Msg.of(TravelText.LATERAL_TOO_CLOSE, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "side", effectiveSideName(pattern), "amplitude", effectiveAmplitude,
                "gap", Math.floor(firstGap), "spacing", minSpacing, "why", whyThatSpacing(minSpacing, waypointMargin),
                "neededStep", neededStep, "sideName", sideName(pattern), "neededSide", neededAmplitude,
                "capped", cappedBySide(configuredAmplitude, effectiveAmplitude, highway));
        }
        if (omitsLastLateralPoint(distance, step, effectiveAmplitude, minSpacing)
            && Math.floor(distance / step) <= 1) {
            // The only pattern point falls so close to the destination that Baritone would land on it
            // before arriving home; and omitting it would leave the route completely straight. There is
            // nothing to cap: it is rejected.
            double lastGap = Math.hypot(distance - Math.floor(distance / step) * step, effectiveAmplitude);
            return Msg.of(TravelText.LATERAL_SINGLE_TOO_CLOSE, "pattern", pattern.name(), "step", stepName(pattern),
                "value", step, "distance", distance, "gap", Math.floor(lastGap), "spacing", minSpacing,
                "why", whyThatSpacing(minSpacing, waypointMargin), "sideName", sideName(pattern),
                "needed", Math.ceil(minSpacing));
        }
        return null;
    }

    /**
     * Where the required spacing comes from. It always names {@code waypoint-margin} with its value,
     * even when the one that rules is the physical floor: it is the setting the player can move to ask
     * for less room, and a reason that does not name it takes that way out away from them.
     */
    private static Msg whyThatSpacing(double minSpacing, double waypointMargin) {
        if (minSpacing > MIN_WAYPOINT_SPACING) {
            return Msg.of(TravelText.WHY_SPACING, "margin", waypointMargin, "landing", BARITONE_LANDING_DISTANCE);
        }
        return Msg.of(TravelText.WHY_SPACING_FLOOR, "margin", waypointMargin, "landing", BARITONE_LANDING_DISTANCE,
            "floor", MIN_WAYPOINT_SPACING);
    }

    /**
     * Whether the last point of a lateral pattern falls so close to the destination that it is not
     * emitted.
     *
     * <p>It is a pointless detour: it takes you the whole amplitude away without gaining progress,
     * right at the end of the trip -where the fewest fireworks are left- and, worse since this fix, it
     * leaves Baritone with two goals close together and one extra landing between them. The threshold
     * is the minimum spacing, the same criterion that governs every other gap of the route: it used to
     * be the amplitude, which had nothing to do with what Baritone can fly.
     *
     * <p>{@code hypot} is used and not the plain remainder because what matters is the real distance
     * between the last waypoint and the destination, which includes the lateral offset. It also makes
     * the remainder's sign irrelevant: with {@code ceil} instead of {@code floor} the remainder comes
     * out negative, and a negative gap is not a short gap.
     */
    private static boolean omitsLastLateralPoint(double distance, double step, double amplitude,
                                                  double minSpacing) {
        double remaining = distance - Math.floor(distance / step) * step;
        return Math.hypot(remaining, amplitude) < minSpacing;
    }

    /**
     * The spiral has the same hole as ZIGZAG and SWERVE through its two settings, and for consistency
     * it gets the same treatment:
     *
     * <ul>
     *   <li><b>Zero effective radius.</b> With the radius capped to zero -a highway destination with
     *       the corridor width at 0- all its steps fall exactly on the destination, because {@code
     *       stepRadius = radius * (1 - fraction)} is zero for any {@code fraction}. It is not a small
     *       spiral: it is 37 copies of the destination, not one turn, not one block away from the
     *       axis.</li>
     *   <li><b>Turns at zero.</b> The radius does shrink, but the angle is {@code 2π * 0 * fraction},
     *       that is zero at every step: the points are spread over the axis itself, between the
     *       destination and the point one radius before. It is exactly the straight approach STRAIGHT
     *       would already make, with 9 decorative waypoints on top.</li>
     * </ul>
     *
     * <p>Negative turns do NOT get in here: {@link #spiralSteps} takes the absolute value for the
     * steps and the angle comes out negative, so the spiral turns the other way. It turns, which is
     * all that is asked of it; that still works and is not rejected.
     *
     * @return the rejection's reason, or {@code null} if the spiral can really be drawn
     */
    private static Msg spiralRejection(double configuredRadius, double effectiveRadius, double turns,
                                           boolean highway) {
        if (effectiveRadius <= 0) {
            return Msg.of(TravelText.SPIRAL_NO_RADIUS, "pattern", FlightPattern.SPIRAL.name(),
                "radius", effectiveRadius, "fix", howToWiden(TravelText.NAME_RADIUS, configuredRadius, highway));
        }
        if (turns == 0) {
            return Msg.of(TravelText.SPIRAL_NO_TURNS, "pattern", FlightPattern.SPIRAL.name(), "turns", turns);
        }
        return null;
    }

    /**
     * The reason for the spiral that ends up without a spiral when spaced out: its steps are so close
     * together that, removing the ones that cannot be flown, none off the axis survives and the route
     * comes out straight.
     *
     * <p>The number needed is <b>the smallest radius with which this spiral is drawn again</b>, and it
     * is searched for by trying radii block by block up to the geometric maximum ({@code distance/2},
     * which is where the spiral would start behind the origin). It is searched for instead of solved
     * because the spacing has no closed form -it depends on which steps survive, which depends on the
     * ones that survived before-, and trying 37 points a few thousand times is free on a path that is
     * only taken to word a rejection. The search is worth it because the alternative is a reason that
     * says "raise the radius" without saying to how much, and that is precisely the rejection this
     * class does not deliver.
     *
     * <p>Who is to blame for the small radius changes the way out: if the destination is a highway one
     * and the corridor width is what is capping it, the setting to move is that width and not the
     * radius. With the default values -radius 1500, 1.5 turns, corridor 300- this is exactly the
     * combination that is rejected: a spiral that can only stray 300 blocks from the axis leaves steps
     * of 8 blocks, and a radius of 338 is needed for there to be a spiral again.
     */
    private static Msg spiralSpacingRejection(Waypoint origin, Waypoint destination, double ux, double uz,
                                                  double nx, double nz, double distance, PatternParams params,
                                                  double effectiveRadius, boolean highway,
                                                  double highwayMaxAmplitude, double minSpacing,
                                                  double waypointMargin) {
        double neededRadius = 0;
        for (double radius = Math.ceil(effectiveRadius) + 1; radius <= distance / 2.0; radius++) {
            List<Waypoint> candidate = spaceOut(origin,
                spiral(destination, ux, uz, nx, nz, radius, params), minSpacing);
            if (leavesTheAxis(candidate, origin, nx, nz)) {
                neededRadius = radius;
                break;
            }
        }

        List<Waypoint> raw = spiral(destination, ux, uz, nx, nz, effectiveRadius, params);
        double shortestStep = Double.MAX_VALUE;
        for (int i = 0; i < raw.size() - 1; i++) {
            shortestStep = Math.min(shortestStep, raw.get(i).distanceTo(raw.get(i + 1)));
        }

        Msg fix;
        if (neededRadius == 0) {
            fix = Msg.of(TravelText.SPIRAL_FIX_SHORT_TRIP, "distance", distance);
        } else if (highway && params.spiralRadius() > highwayMaxAmplitude) {
            fix = Msg.of(TravelText.SPIRAL_FIX_CORRIDOR, "radius", params.spiralRadius(), "max", highwayMaxAmplitude,
                "needed", neededRadius);
        } else {
            fix = Msg.of(TravelText.SPIRAL_FIX_RADIUS, "needed", neededRadius);
        }
        return Msg.of(TravelText.SPIRAL_TOO_CLOSE, "pattern", FlightPattern.SPIRAL.name(), "radius", effectiveRadius,
            "turns", params.spiralTurns(), "step", Math.floor(shortestStep), "spacing", minSpacing,
            "why", whyThatSpacing(minSpacing, waypointMargin), "fix", fix);
    }

    /**
     * The decoy ends up without a decoy when its correction point -its only intermediate waypoint-
     * falls on the origin-destination line, and then the route is the straight line with one extra
     * stop:
     *
     * <ul>
     *   <li><b>Zero fraction.</b> The correction point is {@code origin + direction*distance*0}, that
     *       is the origin itself. It "corrects" without having strayed.</li>
     *   <li><b>An angle that is a multiple of 180 degrees.</b> The decoy's direction is the real
     *       heading (0) or the opposite (180), so the correction point stays on the same axis: there
     *       is nobody to throw off. The multiple is checked and not {@code sin(angle) == 0} because
     *       {@code Math.sin(Math.toRadians(180))} is 1.2e-16, not zero.</li>
     * </ul>
     *
     * <p>A negative fraction does NOT get in here: the correction point stays off the axis, behind the
     * origin. It is an expensive detour, but it really strays from the real heading, which is what the
     * decoy exists for; what still works is capped. A negative angle does not either: it throws off
     * towards the other side and that is it.
     *
     * @return the rejection's reason, or {@code null} if the decoy really throws off
     */
    private static Msg decoyRejection(double angleDegrees, double fraction) {
        if (fraction == 0) {
            return Msg.of(TravelText.DECOY_NO_FRACTION, "pattern", FlightPattern.DECOY.name(), "fraction", fraction);
        }
        if (angleDegrees % 180 == 0) {
            return Msg.of(TravelText.DECOY_NO_ANGLE, "pattern", FlightPattern.DECOY.name(), "angle", angleDegrees);
        }
        return null;
    }

    /**
     * The reason for the decoy whose two legs -origin to correction point, and correction point to
     * destination- are too short to be flown as legs.
     *
     * <p>Here there is nothing to trim: the decoy has a single intermediate waypoint, so it either fits
     * or it does not. And the exact number needed comes out without searching, because <b>both legs
     * are proportional to the trip's distance</b>: the first measures {@code fraction*distance} and the
     * second {@code distance*|fraction*decoy_direction - u|}, so scaling is enough. That is why the
     * reason talks about moving the destination further and not about touching the angle: with the
     * default angle and fraction the decoy needs a trip of about 530 blocks, and whoever runs into it
     * is whoever asks for a decoy to go round the corner.
     */
    private static Msg decoySpacingRejection(double distance, PatternParams params, double shortestGap,
                                                 double minSpacing, double waypointMargin) {
        double neededDistance = Math.ceil(distance * minSpacing / shortestGap);
        return Msg.of(TravelText.DECOY_TOO_CLOSE, "pattern", FlightPattern.DECOY.name(),
            "angle", params.decoyAngleDegrees(), "fraction", params.decoyFraction(), "gap", Math.floor(shortestGap),
            "distance", distance, "spacing", minSpacing, "why", whyThatSpacing(minSpacing, waypointMargin),
            "needed", neededDistance);
    }

    /**
     * The tag line warning that raising the offset will be of no use by itself because the highway
     * corridor is capping it. Without it, the reason sends the player to move a slider that does not
     * change the route, which is worse than telling them nothing.
     */
    private static Msg cappedBySide(double configuredAmplitude, double effectiveAmplitude, boolean highway) {
        if (!highway || configuredAmplitude <= effectiveAmplitude) return Msg.of(TravelText.NOTHING);
        return Msg.of(TravelText.CAPPED_BY_CORRIDOR, "amplitude", effectiveAmplitude);
    }

    /** The way out of the dead end, which changes depending on where the zero comes from: the corridor or the setting. */
    private static Msg howToWiden(TravelText settingName, double configured, boolean highway) {
        if (highway && configured > 0) {
            return Msg.of(TravelText.WIDEN_CORRIDOR, "configured", configured);
        }
        return Msg.of(TravelText.WIDEN_SETTING, "setting", settingName);
    }

    /** What each lateral pattern's step is called in the settings, so that the reason is actionable. */
    private static TravelText stepName(FlightPattern pattern) {
        return pattern == FlightPattern.SWERVE ? TravelText.NAME_LEG : TravelText.NAME_PERIOD;
    }

    /** What each lateral pattern's offset is called in the settings. */
    private static TravelText sideName(FlightPattern pattern) {
        return pattern == FlightPattern.SWERVE ? TravelText.NAME_OFFSET : TravelText.NAME_AMPLITUDE;
    }

    /** The same name with the adjective in agreement, which in Spanish does not come from concatenating. */
    private static TravelText effectiveSideName(FlightPattern pattern) {
        return pattern == FlightPattern.SWERVE ? TravelText.NAME_OFFSET_EFFECTIVE : TravelText.NAME_AMPLITUDE_EFFECTIVE;
    }

    /**
     * ZIGZAG and SWERVE share this function; only the step and the amplitude they receive change. For
     * {@code i} from 1 to {@code floor(distance/period)}, the waypoint is {@code origin + u*(i*period) +
     * n*(amplitude * (i odd ? +1 : -1))}. At the end, always the exact destination.
     *
     * <p>The five cases in which the pattern would not be flown as asked -the step being zero or
     * negative, not a single leg fitting, the effective amplitude being zero, the pattern not fitting
     * whole under the waypoint cap, or its waypoints ending up closer than the minimum spacing- are
     * rejected before getting here, in {@link #lateralRejection}, so this function always generates at
     * least one pattern point with a real offset, far enough from the previous one, and the weave
     * always reaches the destination: no half-woven trip comes out of here.
     *
     * <p>The {@code Math.min} with {@link #MAX_PATTERN_WAYPOINTS} no longer decides anything -the
     * rejection guarantees that the count fits under the cap-, and it stays as a belt against the hang
     * in case someone skipped that rejection: {@code floor(distance/0.0)} is infinite and the {@code
     * (int)} of infinity is {@code Integer.MAX_VALUE}, two billion waypoints that the adapter would
     * send one by one to the chat; with the min they are 500, and with a negative step the count comes
     * out negative and the loop does not run. That is why no test turns it red on its own: it is only
     * noticed if the rejection breaks first, and the rejection's tests take care of that. What would be
     * left in both cases is an absurd and silent route, which is precisely what the rejection prevents.
     */
    private static List<Waypoint> zigzag(Waypoint origin, Waypoint destination, double ux, double uz,
                                          double nx, double nz, double distance, double period, double amplitude,
                                          double minSpacing) {
        List<Waypoint> points = new ArrayList<>();
        int steps = (int) Math.min(Math.floor(distance / period), MAX_PATTERN_WAYPOINTS);
        for (int i = 1; i <= steps; i++) {
            double along = i * period;
            double sign = (i % 2 == 1) ? 1 : -1;
            double x = origin.x() + ux * along + nx * amplitude * sign;
            double z = origin.z() + uz * along + nz * amplitude * sign;
            points.add(new Waypoint(x, z));
        }

        // The last lateral point is omitted when it ends up right next to the destination: see
        // omitsLastLateralPoint, which is where the criterion and its reason live.
        //
        // The "points.size() > 1" guard no longer decides anything: if the pattern has only one point
        // and it falls inside the omission window, lateralRejection has rejected it before getting
        // here -keeping it would make Baritone land twice and removing it would leave the route
        // straight, and neither is acceptable-. It stays as a belt: without it, breaking that rejection
        // would return a straight and silent route instead of a route with one detour too many, which
        // is the worse failure of the two. That is why no test turns it red on its own.
        //
        // Removing the omission altogether IS noticed, and that is covered by
        // aZigzagDoesNotWasteAFinalOutAndBackRightAtTheDestination and
        // theWaypointCoordinatesMatchFloorOfDistanceOverPeriodForANonExactDivision.
        if (points.size() > 1 && omitsLastLateralPoint(distance, period, amplitude, minSpacing)) {
            points.remove(points.size() - 1);
        }

        points.add(destination);
        return points;
    }

    /**
     * Removes from {@code raw} the waypoints that fall less than {@code minSpacing} from the previous
     * one that is kept, starting to measure from {@code origin} itself -the first waypoint also has to
     * be far enough from the starting point, or it would be let go right after take-off-. The last
     * point of {@code raw}, which is the destination, is always kept; the ones that end up right
     * behind it go, one after another, until the final leg measures what it has to measure.
     *
     * <p><b>This is not applied to ZIGZAG or SWERVE</b>, and not by oversight: in a periodic pattern
     * removing one point in two is doubling the step without telling the player. There it is
     * rejected. Here it is used only for the spiral, whose steps are the sampling of a continuous
     * curve: keeping fewer samples of the same stroke does not change the stroke, and the core that
     * is left out -the last degrees, with the radius already almost zero- is not flyable with an
     * elytra however it is configured, because by definition it ends at radius zero.
     */
    private static List<Waypoint> spaceOut(Waypoint origin, List<Waypoint> raw, double minSpacing) {
        Waypoint destination = raw.get(raw.size() - 1);
        List<Waypoint> kept = new ArrayList<>();
        Waypoint anchor = origin;
        for (int i = 0; i < raw.size() - 1; i++) {
            if (anchor.distanceTo(raw.get(i)) < minSpacing) continue;
            kept.add(raw.get(i));
            anchor = raw.get(i);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).distanceTo(destination) < minSpacing) {
            kept.remove(kept.size() - 1);
        }
        kept.add(destination);
        return kept;
    }

    /**
     * Whether any intermediate waypoint off the origin-destination line survives in {@code points}.
     *
     * <p>It is the condition that separates "the spiral has been trimmed" from "the spiral has
     * vanished". Looking at how many points are left is not enough: with the radius capped to the
     * width of a narrow corridor, the only step that survives the spacing is the first one, which is
     * exactly on the axis -one radius from the destination, in a straight line-. The route would be
     * the straight line with a decorative waypoint, and that is rejected, not delivered.
     */
    private static boolean leavesTheAxis(List<Waypoint> points, Waypoint origin, double nx, double nz) {
        for (int i = 0; i < points.size() - 1; i++) {
            double offset = (points.get(i).x() - origin.x()) * nx + (points.get(i).z() - origin.z()) * nz;
            if (Math.abs(offset) > AXIS_TOLERANCE) return true;
        }
        return false;
    }

    /**
     * The radius arrives already capped from {@link #plan}: to {@code distance/2} if the trip is
     * shorter -so the spiral never goes back behind the origin- and, on a highway, also to the
     * corridor width ({@code highwayMaxAmplitude}), because the point furthest from the axis of any
     * step is at most {@code stepRadius} from the destination, and {@code stepRadius <= radius}
     * always, so capping the starting radius is enough for no step to leave the corridor. It is capped
     * there and not here so that the zero-radius rejection and the geometry talk about the same number.
     *
     * <p>It goes straight to {@code destination - u*radius} -which is exactly the spiral's first
     * point, with {@code j=0}- and from there it makes {@code spiralTurns} turns closing in on the
     * destination, with the angle measured from the {@code -u} direction towards {@code n}: at {@code
     * j=0} that gives {@code destination - u*radius} in components, not only in distance, and it is
     * what guarantees that the first point lies BEFORE the destination and not after -measuring from
     * {@code +u} would put that same point one radius PAST the destination.
     */
    private static List<Waypoint> spiral(Waypoint destination, double ux, double uz, double nx, double nz,
                                          double radius, PatternParams params) {
        int steps = spiralSteps(params.spiralTurns());

        List<Waypoint> points = new ArrayList<>();
        for (int j = 0; j <= steps; j++) {
            double fraction = (double) j / steps;
            double angle = 2 * Math.PI * params.spiralTurns() * fraction;
            double stepRadius = radius * (1 - fraction);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // Basis (-u, n): angle 0 points towards -u, and grows turning towards n.
            double x = destination.x() + stepRadius * (cos * -ux + sin * nx);
            double z = destination.z() + stepRadius * (cos * -uz + sin * nz);
            points.add(new Waypoint(x, z));
        }
        return points;
    }

    /** Spiral steps scaled with the turns, with a minimum so as not to degenerate into a polygon. */
    private static int spiralSteps(double turns) {
        return (int) Math.max(MIN_SPIRAL_STEPS, Math.ceil(SPIRAL_STEPS_PER_TURN * Math.abs(turns)));
    }

    /**
     * The decoy is {@code origin + rotate(u, degrees)*distance}. The correction point -the only
     * intermediate waypoint- is {@code origin + (decoy - origin)*fraction}. Waypoints: the correction
     * point, and the destination.
     */
    private static List<Waypoint> decoy(Waypoint origin, Waypoint destination, double ux, double uz,
                                         double distance, PatternParams params) {
        double radians = Math.toRadians(params.decoyAngleDegrees());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double decoyDirectionX = ux * cos - uz * sin;
        double decoyDirectionZ = ux * sin + uz * cos;

        double correctionX = origin.x() + decoyDirectionX * distance * params.decoyFraction();
        double correctionZ = origin.z() + decoyDirectionZ * distance * params.decoyFraction();

        return List.of(new Waypoint(correctionX, correctionZ), destination);
    }
}
