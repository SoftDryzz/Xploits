package com.xploits.travel.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerTest {
    private static final Waypoint ORIGIN = new Waypoint(0, 0);
    private static final double HIGHWAY_MAX = 300;
    /**
     * A corridor wide enough for the SPIRAL to fit inside with the default values. With the 300 of
     * HIGHWAY_MAX -which are the module's default- the spiral is rejected for leaving its steps 8
     * blocks apart, and that has its own test: see
     * aSpiralSqueezedIntoANarrowCorridorIsRefusedBecauseNoElytraCouldFlyIt.
     */
    private static final double HIGHWAY_WIDE = 500;
    private static final double MARGIN = RoutePlanner.DEFAULT_WAYPOINT_MARGIN;
    private static final double TOLERANCE = 0.001;

    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    /** A rejection as the player reads it in Spanish, for the tests that look for words and numbers in it. */
    private static String es(Msg rejection) {
        return rejection == null ? "null" : ES.render(rejection);
    }

    private static Route plan(Destination destination, FlightPattern pattern) {
        return RoutePlanner.plan(ORIGIN, destination, pattern, PatternParams.defaults(), HIGHWAY_MAX, MARGIN);
    }

    private static Waypoint last(Route route) {
        return route.waypoints().get(route.waypoints().size() - 1);
    }

    @Test
    void aStraightRouteIsJustTheDestination() {
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.STRAIGHT);
        assertFalse(route.isRejected());
        assertEquals(1, route.waypoints().size());
        assertEquals(10_000, last(route).x(), TOLERANCE);
    }

    @Test
    void everyPatternEndsExactlyAtTheDestination() {
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = plan(Destination.coordinates(20_000, 5_000), pattern);
            assertFalse(route.isRejected(), pattern + " should not be rejected with coordinates");
            assertEquals(20_000, last(route).x(), TOLERANCE, pattern + " does not end at the destination");
            assertEquals(5_000, last(route).z(), TOLERANCE, pattern + " does not end at the destination");
        }
    }

    @Test
    void zigzagAlternatesSidesOfTheHeading() {
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        List<Waypoint> points = route.waypoints();

        // The heading is +X, so the deviation shows in Z and must alternate in sign.
        double previous = 0;
        int changes = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            double z = points.get(i).z();
            if (previous != 0 && Math.signum(z) != Math.signum(previous)) changes++;
            previous = z;
        }
        assertTrue(changes >= 2, "the zigzag must cross the heading several times, crossings: " + changes);
    }

    @Test
    void zigzagRespectsItsAmplitude() {
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        double amplitude = PatternParams.defaults().amplitude();

        for (Waypoint point : route.waypoints()) {
            assertTrue(Math.abs(point.z()) <= amplitude + TOLERANCE,
                "no point may stray further than the amplitude: " + point.z());
        }
    }

    @Test
    void aTripShorterThanASingleLegIsRefusedInsteadOfSilentlyFlyingStraight() {
        // This test used to enshrine the bug: it accepted a straight, non-rejected route.
        // floor(500/2000)=0, so NOT A SINGLE pattern point is generated and the route comes out
        // straight even though ZIGZAG was asked for. With SWERVE's default leg (5000) that is every
        // trip under 5000 blocks: the player asks for evasion, flies straight and believes they weave.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(500, 0), FlightPattern.ZIGZAG,
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "a trip shorter than one leg must be rejected, not come out straight");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");

        // The reason has to name both numbers and the way out: a rejection that does not say how to
        // get unstuck is almost as bad as silence.
        String reason = es(route.rejection());
        assertTrue(reason.contains("2000"), "the reason must state the configured period: " + reason);
        assertTrue(reason.contains("500"), "the reason must state the trip's distance: " + reason);
        assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
    }

    @Test
    void aShortTripWithSwerveNamesItsOwnSettingNotTheZigzagOne() {
        // The reason is read in the chat and has to point at the setting the player can touch: SWERVE
        // is configured with "tramo" (leg), not with "periodo" (period).
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(4_000, 0), FlightPattern.SWERVE,
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected());
        String reason = es(route.rejection());
        assertTrue(reason.contains("tramo"), "the swerve's reason must talk about its leg: " + reason);
        assertTrue(reason.contains("5000"), "the reason must state the configured leg: " + reason);
        assertTrue(reason.contains("4000"), "the reason must state the trip's distance: " + reason);
    }

    @Test
    void aLateralPatternWithItsAmplitudeCappedToZeroIsRefusedNotDrawnFlat() {
        // You get here without any absurd parameter: a highway destination with the corridor width at
        // 0 caps the effective amplitude to 0. The points are generated -so the "points.size() > 1"
        // guard does not protect against this- but they all fall on the axis: a straight line with
        // decorative waypoints.
        for (FlightPattern pattern : List.of(FlightPattern.ZIGZAG, FlightPattern.SWERVE)) {
            Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), pattern,
                PatternParams.defaults(), 0, MARGIN);

            assertTrue(route.isRejected(), pattern + " with an effective amplitude of 0 must be rejected");
            assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");
            String reason = es(route.rejection());
            assertTrue(reason.contains("0"), "the reason must state the effective amplitude: " + reason);
            assertTrue(reason.toLowerCase().contains("corredor"),
                "the reason must point out where the zero comes from: " + reason);
            assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
        }
    }

    @Test
    void aSpiralWithItsRadiusCappedToZeroIsRefusedLikeAFlatZigzag() {
        // The same hole as the lateral one and, for consistency with the doctrine, the same
        // treatment: with radius 0 the 37 steps fall exactly on the destination. It is not a small
        // spiral, it is a straight line with repeated waypoints.
        Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.SPIRAL,
            PatternParams.defaults(), 0, MARGIN);

        assertTrue(route.isRejected(), "a spiral of radius 0 is a straight line and must be rejected");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");
        String reason = es(route.rejection());
        assertTrue(reason.contains("1500"), "the reason must state the configured radius: " + reason);
        assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
    }

    @Test
    void theSpiralClosesOnTheDestination() {
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.SPIRAL);
        List<Waypoint> points = route.waypoints();
        Waypoint destination = new Waypoint(30_000, 0);

        // The radius must decrease monotonically in the final stretch.
        double previous = Double.MAX_VALUE;
        int decreasing = 0;
        for (Waypoint point : points) {
            double radius = point.distanceTo(destination);
            if (radius < previous) decreasing++;
            previous = radius;
        }
        assertTrue(decreasing >= points.size() - 2, "the radius must close in on the destination");
        assertEquals(0, last(route).distanceTo(destination), TOLERANCE);
    }

    @Test
    void aSpiralBiggerThanTheTripIsCappedInsteadOfOvershooting() {
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(1_000, 0), FlightPattern.SPIRAL,
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

        assertFalse(route.isRejected());
        for (Waypoint point : route.waypoints()) {
            assertTrue(point.x() >= -TOLERANCE, "the spiral must not go back behind the origin: " + point.x());
        }
    }

    @Test
    void theSpiralsFirstWaypointIsExactlyWhereTheStraightLegEnds() {
        // The straight leg goes to destination - u*radius: that point IS the spiral's first one (j=0,
        // radius=radius). There must not be an extra second waypoint 2*radius from the destination:
        // that would leave a jump of a whole "radius" with no purpose before really starting to turn.
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.SPIRAL);
        Waypoint destination = new Waypoint(30_000, 0);
        Waypoint first = route.waypoints().get(0);

        assertEquals(PatternParams.defaults().spiralRadius(), first.distanceTo(destination), TOLERANCE,
            "the spiral's first waypoint must be one radius from the destination, not two");
    }

    @Test
    void theSpiralsFirstWaypointIsExactlyDestinationMinusUTimesRadius() {
        // Pinning the distance (radius) is not enough: measuring the angle from +u instead of -u also
        // gives a first point one radius away, but PAST the destination, not before it. Only checking
        // the components rules out that alternative.
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.SPIRAL);
        Waypoint first = route.waypoints().get(0);
        double radius = PatternParams.defaults().spiralRadius();

        // u = (1,0): the destination is straight along +X from the origin.
        assertEquals(30_000 - radius, first.x(), TOLERANCE,
            "the angle is measured from -u: the first point must lie BEFORE the destination");
        assertEquals(0, first.z(), TOLERANCE);
    }

    @Test
    void theSpiralStepCountScalesWithTheConfiguredTurns() {
        // With a fixed 36 steps and more turns, the angular step grows and the spiral turns into a
        // star polygon. Doubling the turns must translate into more steps, not the same number with
        // more degrees each.
        Route defaultTurns = plan(Destination.coordinates(30_000, 0), FlightPattern.SPIRAL);
        PatternParams manyTurns = new PatternParams(200, 2000, 5000, 800, 1500, 3.0, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(30_000, 0), FlightPattern.SPIRAL,
            manyTurns, HIGHWAY_MAX, MARGIN);

        assertTrue(route.waypoints().size() > defaultTurns.waypoints().size(),
            "twice the turns must translate into more steps");
    }

    @Test
    void theDecoyAimsAwayFirstAndCorrectsLater() {
        Route route = plan(Destination.coordinates(20_000, 0), FlightPattern.DECOY);
        Waypoint correction = route.waypoints().get(0);

        assertTrue(Math.abs(correction.z()) > 1_000,
            "the first leg must point clearly off the real heading, z=" + correction.z());
        assertEquals(20_000, last(route).x(), TOLERANCE);
    }

    @Test
    void theDecoysCorrectionPointIsExactlyComputed() {
        // "It strays more than 1000" is not enough: with 60° instead of 30°, or fraction 0.4 instead
        // of 0.6, that threshold is still met. It pins the exact point.
        Route route = plan(Destination.coordinates(20_000, 0), FlightPattern.DECOY);
        Waypoint correction = route.waypoints().get(0);

        double angle = Math.toRadians(PatternParams.defaults().decoyAngleDegrees());
        double fraction = PatternParams.defaults().decoyFraction();
        // u = (1,0): rotate u by the decoy's angle within the XZ plane.
        double expectedX = Math.cos(angle) * 20_000 * fraction;
        double expectedZ = Math.sin(angle) * 20_000 * fraction;

        assertEquals(expectedX, correction.x(), TOLERANCE);
        assertEquals(expectedZ, correction.z(), TOLERANCE);
    }

    @Test
    void aHighwayDestinationLandsOnItsAxis() {
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.STRAIGHT);
        assertEquals(50_000, last(route).x(), TOLERANCE);
        assertEquals(0, last(route).z(), TOLERANCE);
    }

    @Test
    void theFourAxesPointWhereTheySay() {
        assertEquals(1_000, Destination.highway(Axis.X_PLUS, 1_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(-1_000, Destination.highway(Axis.X_MINUS, 1_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(1_000, Destination.highway(Axis.Z_PLUS, 1_000).resolve(ORIGIN).z(), TOLERANCE);
        assertEquals(-1_000, Destination.highway(Axis.Z_MINUS, 1_000).resolve(ORIGIN).z(), TOLERANCE);
    }

    @Test
    void onAHighwayNoWaypointLeavesTheAllowedCorridorForAnyPattern() {
        // Critical: my brief only asked to cap ZIGZAG and SWERVE, but no pattern may leave the
        // corridor (spec §4.2). Without also capping SPIRAL's radius, with the default values and
        // highwayMaxAmplitude=300, step 6 (angle 90°) goes 1250 blocks off the axis.
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), pattern,
                PatternParams.defaults(), HIGHWAY_WIDE, MARGIN);

            if (pattern == FlightPattern.DECOY) {
                assertTrue(route.isRejected(), "the decoy must be rejected on a highway, not capped");
                continue;
            }

            assertFalse(route.isRejected(), pattern + " should not be rejected on a highway: " + es(route.rejection()));
            for (Waypoint point : route.waypoints()) {
                assertTrue(Math.abs(point.z()) <= HIGHWAY_WIDE + TOLERANCE,
                    pattern + " left the corridor: " + point.z());
            }
        }
    }

    @Test
    void onADiagonalHighwayNoWaypointLeavesTheCorridorEither() {
        // The planner's geometry works with the origin->destination unit vector, so the diagonals
        // SHOULD cap themselves. "Should" is not a guarantee: this test checks it.
        //
        // And it measures the perpendicular distance to the axis, not the Z coordinate. On +X the two
        // match and that is why the cardinal test could look at Z; on a diagonal, a waypoint can be
        // 200 blocks from the axis and 141 from Z, or 700 from Z and right on the axis. Looking at Z
        // here would give green with the corridor broken.
        for (Axis axis : Axis.values()) {
            for (FlightPattern pattern : FlightPattern.values()) {
                Destination destination = Destination.highway(axis, 50_000);
                Route route = RoutePlanner.plan(ORIGIN, destination, pattern,
                    PatternParams.defaults(), HIGHWAY_WIDE, MARGIN);

                if (pattern == FlightPattern.DECOY) {
                    assertTrue(route.isRejected(),
                        "the decoy must be rejected on a highway along " + axis + " too");
                    continue;
                }

                assertFalse(route.isRejected(),
                    pattern + " along " + axis + " should not be rejected: " + es(route.rejection()));

                // n = (-uz, ux), the normal to the axis; the offset is the waypoint's projection onto it.
                Waypoint end = destination.resolve(ORIGIN);
                double distance = ORIGIN.distanceTo(end);
                double nx = -(end.z() - ORIGIN.z()) / distance;
                double nz = (end.x() - ORIGIN.x()) / distance;

                for (Waypoint point : route.waypoints()) {
                    double offset = (point.x() - ORIGIN.x()) * nx + (point.z() - ORIGIN.z()) * nz;
                    assertTrue(Math.abs(offset) <= HIGHWAY_WIDE + TOLERANCE,
                        pattern + " left the corridor of " + axis + " by " + offset + " blocks");
                }
            }
        }
    }

    @Test
    void aDiagonalRouteIsTheCardinalOneRotatedFortyFiveDegrees() {
        // The same pattern along a diagonal axis has to give the same drawing as along a cardinal one,
        // rotated 45 degrees: same number of waypoints and same offsets from the axis. If the geometry
        // depended on the coordinates and not on the unit vector, two different routes would come out
        // here.
        Route cardinal = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000),
            FlightPattern.ZIGZAG, PatternParams.defaults(), HIGHWAY_WIDE, MARGIN);
        Route diagonal = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS_Z_PLUS, 50_000),
            FlightPattern.ZIGZAG, PatternParams.defaults(), HIGHWAY_WIDE, MARGIN);

        assertEquals(cardinal.waypoints().size(), diagonal.waypoints().size(),
            "the diagonal must draw the same zigzag as the cardinal one");
        for (int i = 0; i < cardinal.waypoints().size(); i++) {
            assertEquals(ORIGIN.distanceTo(cardinal.waypoints().get(i)),
                ORIGIN.distanceTo(diagonal.waypoints().get(i)), TOLERANCE,
                "waypoint " + i + " is not at the same distance from the origin on both axes");
        }
    }

    @Test
    void aRelativeDestinationPlansTheSameRouteAsTheAbsoluteOneItResolvesTo() {
        // The planner does not tell modes apart: it receives an origin and an already resolved
        // destination. This test pins that boundary -the resolution lives in Destination, with its own
        // test- and at the same time stops anyone from putting a per-mode branch here.
        Waypoint start = new Waypoint(1_000, -2_000);
        Route relative = RoutePlanner.plan(start, Destination.relative(20_000, 5_000),
            FlightPattern.SWERVE, PatternParams.defaults(), HIGHWAY_MAX, MARGIN);
        Route absolute = RoutePlanner.plan(start, Destination.coordinates(21_000, 3_000),
            FlightPattern.SWERVE, PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

        assertFalse(relative.isRejected(), es(relative.rejection()));
        assertEquals(absolute.waypoints().size(), relative.waypoints().size());
        for (int i = 0; i < absolute.waypoints().size(); i++) {
            assertEquals(absolute.waypoints().get(i).x(), relative.waypoints().get(i).x(), TOLERANCE);
            assertEquals(absolute.waypoints().get(i).z(), relative.waypoints().get(i).z(), TOLERANCE);
        }
    }

    @Test
    void aRelativeDestinationOfZeroBehavesLikeAnyOtherDestinationEqualToTheOrigin() {
        // An offset of (0, 0) is a destination equal to the origin, which is the same case that already
        // arrived through coordinates set where the player is or through a highway distance of 0. It
        // opens no new door and is not rejected: a zero-block trip is exactly what was asked for,
        // delivered as it is. The doctrine rejects delivering SOMETHING ELSE while keeping quiet about
        // it, not delivering what was asked for.
        Waypoint start = new Waypoint(1_000, -2_000);
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = RoutePlanner.plan(start, Destination.relative(0, 0), pattern,
                PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

            assertFalse(route.isRejected(), pattern + " should not reject a null offset");
            assertEquals(1, route.waypoints().size(), pattern + " should not make up waypoints");
            assertEquals(start.x(), route.waypoints().get(0).x(), TOLERANCE);
            assertEquals(start.z(), route.waypoints().get(0).z(), TOLERANCE);
        }

        // And it is the same outcome as the other two paths to the same point, which is what makes the
        // new mode consistent instead of an exception.
        assertFalse(RoutePlanner.plan(start, Destination.highway(Axis.X_PLUS_Z_MINUS, 0),
            FlightPattern.ZIGZAG, PatternParams.defaults(), HIGHWAY_MAX, MARGIN).isRejected());
        assertFalse(RoutePlanner.plan(start, Destination.coordinates(start.x(), start.z()),
            FlightPattern.ZIGZAG, PatternParams.defaults(), HIGHWAY_MAX, MARGIN).isRejected());
    }

    @Test
    void swerveUsesLegLengthAndLateralOffsetNotAmplitudeAndPeriod() {
        // Without this test, swapping the swerve's parameters (using amplitude/period instead of
        // lateralOffset/legLength) passed every test all the same. Distance chosen (13 000) so that
        // the last leg does not end up so close to the destination as to be omitted (see
        // aZigzagDoesNotWasteAFinalOutAndBackRightAtTheDestination).
        Route route = plan(Destination.coordinates(13_000, 0), FlightPattern.SWERVE);
        List<Waypoint> points = route.waypoints();

        double legLength = PatternParams.defaults().legLength(); // 5000
        double lateralOffset = PatternParams.defaults().lateralOffset(); // 800
        int expectedLegs = (int) Math.floor(13_000 / legLength); // 2

        assertEquals(expectedLegs + 1, points.size(), "swerve must use legLength as its step, not period");
        for (int i = 0; i < points.size() - 1; i++) {
            assertEquals(lateralOffset, Math.abs(points.get(i).z()), TOLERANCE,
                "swerve must use lateralOffset as its amplitude, not amplitude");
        }
    }

    @Test
    void aZeroOrNegativeStepIsRefusedInsteadOfSilentlyFlyingStraight() {
        // This test used to enshrine the bug: with the step at 0 the route came out straight and NOT
        // rejected, the same silent downgrade the other doors already reject. The player sets the
        // period to 0, flies straight and believes they weave. A zero step does not work when capped:
        // it does not work, so it is rejected with a reason.
        //
        // What was worth keeping from the previous version is kept: the point of that straight branch
        // was not to hang the client -(int) Math.floor(d/0.0) is Integer.MAX_VALUE-, and a rejection
        // has to avoid the hang just as well. The deadline checks it: the rejection comes out without
        // entering any loop, long before two seconds.
        record Case(FlightPattern pattern, PatternParams params, String setting, String value) {}
        List<Case> cases = List.of(
            new Case(FlightPattern.ZIGZAG, new PatternParams(200, 0, 5000, 800, 1500, 1.5, 30, 0.6),
                "periodo", "0"),
            new Case(FlightPattern.ZIGZAG, new PatternParams(200, -2000, 5000, 800, 1500, 1.5, 30, 0.6),
                "periodo", "-2000"),
            new Case(FlightPattern.SWERVE, new PatternParams(200, 2000, 0, 800, 1500, 1.5, 30, 0.6),
                "tramo", "0"),
            new Case(FlightPattern.SWERVE, new PatternParams(200, 2000, -5, 800, 1500, 1.5, 30, 0.6),
                "tramo", "-5"));

        for (Case testCase : cases) {
            String who = testCase.pattern() + " with " + testCase.setting() + " at " + testCase.value();
            Route route = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> RoutePlanner.plan(ORIGIN, Destination.coordinates(10_000, 0), testCase.pattern(),
                    testCase.params(), HIGHWAY_MAX, MARGIN),
                who + " must be rejected at once, without entering any loop");

            assertTrue(route.isRejected(), who + " must be rejected, not silently come out straight");
            assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");

            String reason = es(route.rejection());
            assertTrue(reason.contains(testCase.setting()),
                "the reason must name the pattern's own setting: " + reason);
            assertTrue(reason.contains(testCase.value()), "the reason must state the value it has: " + reason);
            assertTrue(reason.contains("recta"), "the reason must state the concrete way out: " + reason);
            assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
        }
    }

    @Test
    void aSwerveWithItsStepAtZeroNamesItsLegNotTheZigzagPeriod() {
        // The reason is read in the chat: it has to point at the setting the player can touch. Without
        // this, a generic reason ("the step") would pass the test above through the SWERVE path.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(10_000, 0), FlightPattern.SWERVE,
            new PatternParams(200, 2000, 0, 800, 1500, 1.5, 30, 0.6), HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected());
        assertFalse(es(route.rejection()).contains("periodo"),
            "the swerve is not configured with a period: " + es(route.rejection()));
    }

    @Test
    void aSpiralWithZeroTurnsIsRefusedBecauseItNeverLeavesTheAxis() {
        // The same hole as the zero step, through the spiral's other setting: the angle is
        // 2*PI*0*fraction, that is 0 at every step, and the 9 points are spread over the axis itself
        // between the destination and the point one radius before. It is STRAIGHT's straight approach
        // with decorative waypoints on top.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(30_000, 0), FlightPattern.SPIRAL,
            new PatternParams(200, 2000, 5000, 800, 1500, 0, 30, 0.6), HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "a spiral that does not turn is a straight line and must be rejected");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");
        String reason = es(route.rejection());
        assertTrue(reason.contains("vueltas"), "the reason must name the spiral's setting: " + reason);
        assertTrue(reason.contains("0"), "the reason must state the value it has: " + reason);
        assertTrue(reason.contains("recta"), "the reason must state the concrete way out: " + reason);
        assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
    }

    @Test
    void aDecoyWhoseCorrectionPointLandsOnTheAxisIsRefusedInsteadOfFlyingStraight() {
        // The decoy has the same hole through its two settings: with the fraction at 0 the correction
        // point IS the origin, and with the angle at 0 (or 180) it falls on the origin-destination line
        // itself. In both cases the "decoy" throws nobody off and the route is the straight line.
        PatternParams zeroFraction = new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, 0);
        Route byFraction = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.DECOY,
            zeroFraction, HIGHWAY_MAX, MARGIN);
        assertTrue(byFraction.isRejected(), "a fraction of 0 leaves the decoy at the origin");
        assertTrue(byFraction.waypoints().isEmpty(), "a rejected route carries no points");
        assertTrue(es(byFraction.rejection()).contains("fracción"),
            "the reason must name the setting: " + es(byFraction.rejection()));
        assertTrue(es(byFraction.rejection()).contains("recta"),
            "the reason must state the concrete way out: " + es(byFraction.rejection()));

        for (double degrees : new double[] {0, 180}) {
            PatternParams flatAngle = new PatternParams(200, 2000, 5000, 800, 1500, 1.5, degrees, 0.6);
            Route byAngle = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.DECOY,
                flatAngle, HIGHWAY_MAX, MARGIN);

            assertTrue(byAngle.isRejected(), "an angle of " + degrees + " degrees does not stray from the axis");
            String reason = es(byAngle.rejection());
            assertTrue(reason.contains("ángulo"), "the reason must name the setting: " + reason);
            assertTrue(reason.contains(String.valueOf((long) degrees)),
                "the reason must state the value it has: " + reason);
            assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
        }
    }

    @Test
    void negativeTurnsAndANegativeDecoyFractionStillDrawARealDetourSoTheyAreNotRefused() {
        // The rejection is for what comes out straight, not for every odd number: with negative turns
        // the spiral turns the other way -it turns, which is all that is asked of it- and with a
        // negative fraction the correction point stays off the axis, behind the origin. Expensive, but
        // it throws off. Nobody should turn these rejections into a broad-brush "<= 0".
        Route spiral = RoutePlanner.plan(ORIGIN, Destination.coordinates(30_000, 0), FlightPattern.SPIRAL,
            new PatternParams(200, 2000, 5000, 800, 1500, -1.5, 30, 0.6), HIGHWAY_MAX, MARGIN);
        assertFalse(spiral.isRejected(), "a reversed spiral is still a spiral");
        assertTrue(spiral.waypoints().stream().anyMatch(point -> Math.abs(point.z()) > 1),
            "the reversed spiral must really stray from the axis");

        Route decoy = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.DECOY,
            new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, -0.6), HIGHWAY_MAX, MARGIN);
        assertFalse(decoy.isRejected(), "a negative fraction points backwards, but it points off the axis");
        assertTrue(Math.abs(decoy.waypoints().get(0).z()) > 1_000,
            "the correction point must lie clearly off the real heading");
    }

    @Test
    void aPatternThatDoesNotFitUnderTheWaypointCapIsRefusedInsteadOfUndulatingOnlyTheFirstHalf() {
        // This test used to enshrine the bug: it accepted the route and only checked that the COUNT of
        // waypoints was capped, without looking at the shape that came out. And the cap caps the
        // count, not the pattern's reach: with the slider's minimum period (100) and a destination
        // 100 000 blocks away 1000 side changes are needed, they were truncated to 500, and the zigzag
        // weaved the first 50 000 while the other 50 000 came out in a perfect straight line pointing
        // at the base -the stretch that arrives home, the worst half to leave a straight line in.
        // Without a rejection and without a warning, while spec §5 promises "the pattern applies over
        // the whole journey".
        PatternParams shortPeriod = new PatternParams(200, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(100_000, 0), FlightPattern.ZIGZAG,
            shortPeriod, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "a pattern that would only cover half the route must be rejected");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");

        // The reason, with the same bar as the other three: the setting the player can touch, its
        // current value, and the concrete number to raise it to in order to get unstuck.
        String reason = es(route.rejection());
        assertTrue(reason.contains("periodo"), "the reason must name the setting: " + reason);
        assertTrue(reason.contains("100 bloques"), "the reason must state the period it has: " + reason);
        assertTrue(reason.contains("100000"), "the reason must state the trip's distance: " + reason);
        assertTrue(reason.contains("200 bloques"), "the reason must state how far to raise it: " + reason);
        assertTrue(reason.contains("recta"), "the reason must state what would go wrong: " + reason);
        assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
    }

    @Test
    void aSwerveThatOverflowsTheCapNamesItsLegAndTheLegLengthItWouldNeed() {
        // The reason is read in the chat: SWERVE is configured with "tramo" (leg), not with "periodo"
        // (period), and the number it needs is its own -ceil(5 000 000/500) = 10 000-, not the
        // zigzag's.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(5_000_000, 0), FlightPattern.SWERVE,
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "1000 legs do not fit under the cap");
        String reason = es(route.rejection());
        assertTrue(reason.contains("tramo"), "the swerve's reason must talk about its leg: " + reason);
        assertFalse(reason.contains("periodo"), "the swerve is not configured with a period: " + reason);
        assertTrue(reason.contains("10000 bloques"), "the reason must state how far to raise the leg: " + reason);
    }

    @Test
    void aLateralPatternThatFitsUnderTheCapKeepsUndulatingRightUpToTheDestination() {
        // The other side of the rejection, and the test that looks at the SHAPE and not the count:
        // with the period right at the limit (exactly 500 side changes) the route is accepted, and then
        // it has to weave to the end. If someone goes back to truncating the count instead of
        // rejecting, the jump between two consecutive waypoints gives away the straight line that
        // appears at the end.
        //
        // Period 250 and a trip of 125 000 -before, 200 and 100 000- so that there are still exactly
        // 500 side changes and also the waypoints end up far enough apart: with period 200 and
        // amplitude 200 the gap is hypot(200, 200) = 283 blocks, below the minimum spacing, and this
        // route would be rejected for something else before getting to what this test looks at.
        PatternParams borderline = new PatternParams(200, 250, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(125_000, 0), FlightPattern.ZIGZAG,
            borderline, HIGHWAY_MAX, MARGIN);

        assertFalse(route.isRejected(), "exactly 500 side changes do fit: " + es(route.rejection()));
        List<Waypoint> points = route.waypoints();
        assertTrue(points.size() <= RoutePlanner.MAX_PATTERN_WAYPOINTS + 1,
            "the cap still holds, the rejection does not repeal it: " + points.size());

        // No straight stretch may exceed two periods: one for the normal progress and another for the
        // last point omitted when it falls right next to the destination. Half a straight route does
        // not get past that.
        double previousX = ORIGIN.x();
        for (Waypoint point : points) {
            assertTrue(point.x() - previousX <= 2 * borderline.period() + TOLERANCE,
                "straight gap between waypoints: from " + previousX + " to " + point.x());
            previousX = point.x();
        }

        // And the deviation is still alive in the last third, not only at the start of the trip.
        List<Waypoint> lastThird = points.subList(points.size() * 2 / 3, points.size() - 1);
        for (Waypoint point : lastThird) {
            assertEquals(borderline.amplitude(), Math.abs(point.z()), TOLERANCE,
                "the last third must still stray the whole amplitude: " + point.z());
        }
    }

    @Test
    void theWaypointCoordinatesMatchFloorOfDistanceOverPeriodForANonExactDivision() {
        // It pins the exact coordinates, not just the count. The distance is chosen so that floor and
        // ceil give different lists: with d=10500 floor's remainder was 500, a final gap of
        // hypot(500, 200) = 538 that is not omitted, and both branches ended up giving the same.
        //
        // With d=10100 they differ:
        //   floor(10100/2000)=5 -> remainder 100 -> final gap hypot(100, 200) = 224, below the
        //       minimum spacing -> the 5th point is omitted: 4 pattern points + destination = 5.
        //   ceil(10100/2000)=6  -> remainder 10100-12000 = -1500 -> final gap hypot(1500, 200) = 1513,
        //       well above the spacing -> nothing is omitted: 6 pattern points + destination = 7.
        // ceil's list matches floor's 5 neither in size nor in coordinates. The gap is measured with
        // hypot and not with the bare remainder precisely for this: a negative remainder is not a
        // short gap, and hypot sweeps the sign away without needing a separate guard.
        Route route = plan(Destination.coordinates(10_100, 0), FlightPattern.ZIGZAG);
        List<Waypoint> points = route.waypoints();

        double[][] expected = {
            {2_000, 200}, {4_000, -200}, {6_000, 200}, {8_000, -200}, {10_100, 0},
        };
        assertEquals(expected.length, points.size(), "number of waypoints");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], points.get(i).x(), TOLERANCE, "waypoint " + i + " (x)");
            assertEquals(expected[i][1], points.get(i).z(), TOLERANCE, "waypoint " + i + " (z)");
        }
    }

    @Test
    void aZigzagDoesNotWasteAFinalOutAndBackRightAtTheDestination() {
        // distance=10000, period=2000: step 5 (the last) falls exactly level with the destination and
        // would stray the 200 blocks of amplitude without gaining any progress. Without the omission
        // there would be 6 points (5 pattern points + destination); with it, 5. And since the landing
        // fix there is a second reason to omit it: those 200 blocks to the destination are less than
        // the minimum spacing, so Baritone would land there and again at the destination.
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        assertEquals(5, route.waypoints().size(), "the last detour, right next to the destination, must be omitted");
    }

    @Test
    void aShortSwerveTripKeepsItsOnlyLateralPointInsteadOfDegradingToAStraightLine() {
        // legLength=5000, lateralOffset=800 (default), trip of 5200: the only pattern point (i=1)
        // falls with a remainder of 200 along the axis, but hypot(200, 800) = 824 blocks from the
        // destination, above the minimum spacing. So it is kept: Baritone can fly from it to the
        // destination without landing on the way, and omitting it would leave a completely straight
        // route even though SWERVE was asked for -a silent downgrade, the same one we reject with the
        // decoy on a highway.
        //
        // The omission criterion is the real gap to the destination, not the remainder along the axis:
        // with the bare remainder (200 < 800) this point would be omitted and the route would come out
        // straight, which is how it was before the gaps were really measured.
        Route route = plan(Destination.coordinates(5_200, 0), FlightPattern.SWERVE);
        List<Waypoint> points = route.waypoints();

        assertEquals(2, points.size(), "the only lateral point must be kept, not degraded to a straight line");
        assertEquals(800, Math.abs(points.get(0).z()), TOLERANCE);
    }

    @Test
    void aDestinationAtAnExactMultipleOfTheLegPaysTheFullOutAndBackOnPurpose() {
        // The conscious toll of the previous decision, pinned so that nobody "fixes" it unknowingly:
        // legLength=5000 and d=5000 give a single pattern point (i=1) that falls EXACTLY level with the
        // destination, remainder 0. That is 800 blocks out and 800 back with no progress nor weave.
        // The omission does not act because the gap to the destination is the offset's 800, above the
        // minimum spacing: the detour is flown whole and without landing in the middle, and that is
        // better than a straight trip the player believes weaves.
        Route route = plan(Destination.coordinates(5_000, 0), FlightPattern.SWERVE);
        List<Waypoint> points = route.waypoints();

        assertFalse(route.isRejected(), "the leg fits exactly once: there is a pattern, nothing to reject");
        assertEquals(2, points.size());
        assertEquals(5_000, points.get(0).x(), TOLERANCE, "the detour falls exactly level with the destination");
        assertEquals(800, points.get(0).z(), TOLERANCE);
        assertEquals(5_000, points.get(1).x(), TOLERANCE);
        assertEquals(0, points.get(1).z(), TOLERANCE);
    }

    @Test
    void theDecoyIsRefusedOnAHighwayNotSilentlyDowngraded() {
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.DECOY);

        assertTrue(route.isRejected(), "the decoy would leave the axis and must be rejected");
        assertTrue(es(route.rejection()).toLowerCase().contains("autopista"),
            "the reason must explain it: " + es(route.rejection()));
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");
    }

    @Test
    void theMarginClearsBaritonesLandingDistanceWithRoomToSpare() {
        // The number the whole fix hangs on: Baritone starts landing 48 blocks from its goal (read from
        // its bytecode: the constant 2304.0 = 48² right before "Path complete, searching for safe
        // landing spot..."), so any margin below that arrives late by definition. The default margin
        // used to be 30: it landed at every waypoint.
        assertEquals(48, RoutePlanner.BARITONE_LANDING_DISTANCE, TOLERANCE);
        assertTrue(RoutePlanner.MIN_WAYPOINT_MARGIN > RoutePlanner.BARITONE_LANDING_DISTANCE,
            "even the lowest margin allowed has to beat Baritone's 48");
        assertTrue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN >= 2 * RoutePlanner.BARITONE_LANDING_DISTANCE,
            "the default one, with slack for Baritone's route recalculation");

        // And the minimum spacing is twice the margin, never less than the physical floor: the margin
        // is spent twice -on letting go of waypoint N you are already one margin from it- and without
        // the double the waypoints are consumed two at a time.
        assertEquals(2 * RoutePlanner.DEFAULT_WAYPOINT_MARGIN,
            RoutePlanner.minimumSpacing(RoutePlanner.DEFAULT_WAYPOINT_MARGIN), TOLERANCE);
        assertEquals(1_000, RoutePlanner.minimumSpacing(500), TOLERANCE);
        assertEquals(RoutePlanner.MIN_WAYPOINT_SPACING, RoutePlanner.minimumSpacing(10), TOLERANCE,
            "it does not go below the physical floor even if the margin is ridiculous");
    }

    @Test
    void onlyTheLastWaypointGetsTheArrivalMarginBecauseItIsTheOnlyOneBaritoneShouldLandOn() {
        // The other half of the fix, and pure arithmetic, so it lives here and not in the adapter:
        // intermediate waypoints are let go of waypoint-margin blocks early so that Baritone never gets
        // to land on them, and the last one is NOT -landing there is what was asked for, and getting
        // 150 blocks ahead would mean sending it "cancel" in the middle of the descent and dropping the
        // player in mid-air-.
        for (int index = 0; index < 4; index++) {
            assertEquals(MARGIN, RoutePlanner.reachedMargin(index, 5, MARGIN), TOLERANCE,
                "intermediate waypoint " + index + " must be let go of with the whole margin");
        }
        assertEquals(RoutePlanner.ARRIVAL_MARGIN, RoutePlanner.reachedMargin(4, 5, MARGIN), TOLERANCE,
            "the last waypoint is the destination: nothing is brought forward there");

        // And a one-waypoint route -STRAIGHT- is its own destination: arrival margin, not a passing one.
        assertEquals(RoutePlanner.ARRIVAL_MARGIN, RoutePlanner.reachedMargin(0, 1, MARGIN), TOLERANCE);
    }

    @Test
    void noAcceptedRouteLeavesTwoWaypointsCloserThanBaritoneCanFly() {
        // The invariant that makes the margin worth anything, measured over the whole route and from
        // the origin itself: if two consecutive waypoints are less than twice the margin apart, the
        // tick that lets go of the first also lets go of the second and the pattern is consumed in a
        // burst without being flown. Before this fix the default spiral ended with 42-block steps.
        // Twice the margin written by hand and not minimumSpacing(MARGIN): if this test asked the same
        // function that is being checked, moving it would not turn it red.
        double spacing = 2 * MARGIN;
        for (FlightPattern pattern : FlightPattern.values()) {
            for (double distance : new double[] {5_000, 10_100, 30_000, 120_000}) {
                Route route = plan(Destination.coordinates(distance, 0), pattern);
                if (route.isRejected()) continue;

                Waypoint previous = ORIGIN;
                for (Waypoint point : route.waypoints()) {
                    assertTrue(previous.distanceTo(point) >= spacing - TOLERANCE,
                        pattern + " at " + distance + " leaves a gap of " + previous.distanceTo(point)
                            + " blocks, and " + spacing + " are needed");
                    previous = point;
                }
            }
        }
    }

    @Test
    void theSpiralKeepsItsTurnsButDropsTheUnflyableCoreInsteadOfLandingOnIt() {
        // The spiral ends at radius zero by definition, so its last steps are a few blocks apart: with
        // the default values, the last ones are 42 blocks, less than the 48 at which Baritone is
        // already landing. No setting fixes it -it is the shape of the curve-, so those steps are not
        // emitted.
        //
        // What CANNOT happen is the trim taking the spiral down with it: it has to keep turning around
        // the destination, crossing the axis on both sides.
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.SPIRAL);
        List<Waypoint> points = route.waypoints();
        assertFalse(route.isRejected());

        assertTrue(points.size() < 37, "the core's steps, unflyable, must not be emitted: " + points.size());
        assertTrue(points.stream().anyMatch(p -> p.z() > 100), "the spiral must pass on one side of the axis");
        assertTrue(points.stream().anyMatch(p -> p.z() < -100), "and on the other");
        assertEquals(0, last(route).distanceTo(new Waypoint(30_000, 0)), TOLERANCE,
            "and end at the exact destination, which is where Baritone should land");
    }

    @Test
    void aTightZigzagIsRefusedInsteadOfHavingItsPeriodStretchedBehindThePlayersBack() {
        // A zigzag with period 100 and amplitude 50 leaves the waypoints hypot(100, 50) = 111 blocks
        // apart: Baritone would land on all of them. The tempting way out -keeping one in three, which
        // would give gaps of 300- is exactly the silent substitution this class does not deliver: it
        // would be a zigzag with period 300 when the player set 100. It is rejected.
        //
        // The trip is 20 000 so that it fits under the waypoint cap (200 side changes) and the
        // rejection that fires is this one and not the cap's.
        PatternParams tight = new PatternParams(50, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            tight, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "a zigzag that cannot be flown must be rejected, not stretched");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");

        String reason = es(route.rejection());
        assertTrue(reason.contains("periodo"), "the reason must name the setting: " + reason);
        assertTrue(reason.contains("100 bloques"), "the reason must state the period it has: " + reason);
        assertTrue(reason.contains("111"), "the reason must state the gap it leaves: " + reason);
        assertTrue(reason.contains("300"), "the reason must state the spacing needed: " + reason);
        assertTrue(reason.contains("296"), "the reason must state how far to raise the period: " + reason);
        assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);
    }

    @Test
    void theMotiveOfATightSwerveNamesItsLegAndItsOffsetNotTheZigzagOnes() {
        // The reason is read in the chat and has to point at the settings the player can touch: SWERVE
        // is configured with leg and lateral offset, not with period and amplitude.
        PatternParams tight = new PatternParams(200, 2000, 100, 50, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.SWERVE,
            tight, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected());
        String reason = es(route.rejection());
        assertTrue(reason.contains("tramo"), "the swerve's reason must talk about its leg: " + reason);
        assertTrue(reason.contains("desvío lateral"), "and about its lateral offset: " + reason);
        assertFalse(reason.contains("periodo"), "the swerve is not configured with a period: " + reason);
    }

    @Test
    void aTightLateralPatternOnAHighwaySaysThatWideningItsSideAloneWouldNotHelp() {
        // With the corridor capping the offset, "raise the amplitude to 283" sends the player to move a
        // slider that changes nothing: the corridor will keep capping it at 50. The reason has to say
        // that the corridor width must be raised too, or it is a rejection that does not get anyone
        // unstuck.
        PatternParams tight = new PatternParams(200, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 20_000), FlightPattern.ZIGZAG,
            tight, 50, MARGIN);

        assertTrue(route.isRejected());
        String reason = es(route.rejection());
        assertTrue(reason.toLowerCase().contains("corredor"),
            "the reason must say that the corridor caps the offset: " + reason);
        assertTrue(reason.contains("50"), "and to how much it caps it: " + reason);
    }

    @Test
    void aLateralPatternWhoseOnlyDetourWouldSitOnTopOfTheDestinationIsRefused() {
        // Leg 5000 and offset 100 on a trip of 5000: the only pattern point falls exactly level with
        // the destination, 100 blocks from it. Keeping it makes Baritone land there and again at the
        // destination; omitting it leaves the route straight even though SWERVE was asked for. Neither
        // will do, so it is rejected with the number to raise the offset to.
        PatternParams narrow = new PatternParams(200, 2000, 5000, 100, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(5_000, 0), FlightPattern.SWERVE,
            narrow, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "a detour Baritone would turn into a landing must be rejected");
        String reason = es(route.rejection());
        assertTrue(reason.contains("desvío lateral"), "the reason must name the setting: " + reason);
        assertTrue(reason.contains("100 bloques"), "the reason must state the gap it leaves: " + reason);
        assertTrue(reason.contains("300"), "the reason must state how far to raise it: " + reason);
        assertTrue(reason.contains("recta"), "the reason must state what would go wrong: " + reason);
    }

    @Test
    void aSpiralSqueezedIntoANarrowCorridorIsRefusedBecauseNoElytraCouldFlyIt() {
        // With the default corridor width (300) the spiral is capped to radius 300, and a spiral of
        // radius 300 with one and a half turns leaves steps of 8 blocks. Removing the unflyable ones
        // only the first survives, which is exactly on the axis: the route would be the straight line
        // with a decorative waypoint. Before this fix it was delivered, and Baritone landed at almost
        // every step; now it is rejected saying how far to raise the corridor.
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.SPIRAL);

        assertTrue(route.isRejected(), "no elytra flies a spiral of radius 300");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");

        String reason = es(route.rejection());
        assertTrue(reason.contains("pasos de 8 bloques"), "the reason must state the step it leaves: " + reason);
        assertTrue(reason.toLowerCase().contains("corredor"),
            "the reason must point out who caps the radius: " + reason);
        assertTrue(reason.contains("338"), "the reason must state how far to raise that width: " + reason);

        // And with the corridor above that number it flies again: the rejection is really actionable,
        // not a "cannot be done" with a made-up number.
        Route wider = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.SPIRAL,
            PatternParams.defaults(), 338, MARGIN);
        assertFalse(wider.isRejected(), "with the width the reason gives it must fly: " + es(wider.rejection()));
    }

    @Test
    void aDecoyOnATooShortTripIsRefusedBecauseItsCorrectionPointWouldBeALandingSpot() {
        // The decoy's two legs are proportional to the trip's distance, so on a short trip both fall
        // short at once: with the default 30° and 60%, a 500-block trip leaves the correction point 283
        // from the destination. Baritone would land there instead of passing through it, which is
        // precisely the opposite of a decoy.
        Route route = plan(Destination.coordinates(500, 0), FlightPattern.DECOY);

        assertTrue(route.isRejected(), "a 500-block decoy cannot be flown");
        assertTrue(route.waypoints().isEmpty(), "a rejected route carries no points");

        String reason = es(route.rejection());
        assertTrue(reason.contains("283"), "the reason must state the leg it leaves: " + reason);
        assertTrue(reason.contains("500"), "the reason must state the trip's distance: " + reason);
        assertTrue(reason.contains("530"), "the reason must state how far to move the destination: " + reason);
        assertTrue(reason.contains("STRAIGHT"), "the reason must offer a way out: " + reason);

        // And at the distance the reason gives it flies again.
        assertFalse(plan(Destination.coordinates(530, 0), FlightPattern.DECOY).isRejected(),
            "the reason's number has to be the right one");
    }

    @Test
    void aBiggerMarginDemandsMoreRoomAndRefusesPatternsThatFlewWithTheDefaultOne() {
        // The margin is a setting, so the spacing the geometry demands moves with it: whoever raises
        // it to 500 asks for gaps of 1000 blocks and some patterns will no longer fit. That is said,
        // with the setting named, instead of silently flying a trimmed pattern.
        PatternParams modest = new PatternParams(200, 400, 5000, 800, 1500, 1.5, 30, 0.6);
        assertFalse(RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            modest, HIGHWAY_MAX, MARGIN).isRejected(), "with the default margin it fits");

        Route tight = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            modest, HIGHWAY_MAX, 500);
        assertTrue(tight.isRejected(), "with the margin at 500 it no longer fits");
        assertTrue(es(tight.rejection()).contains("waypoint-margin"),
            "the reason must name the setting that narrowed it: " + es(tight.rejection()));
        assertTrue(es(tight.rejection()).contains("500"), "and the value it has: " + es(tight.rejection()));
    }

    @Test
    void aDestinationEqualToTheOriginDoesNotBlowUp() {
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(0, 0), pattern,
                PatternParams.defaults(), HIGHWAY_MAX, MARGIN);
            assertFalse(route.isRejected(), pattern + " should not reject a null destination");
        }
    }

    @Test
    void aHighwayDestinationWithoutAnAxisIsRejectedEagerly() {
        // Before: a highway destination without an axis blew up with a confusing NullPointerException
        // inside resolve()'s switch. Now it fails on construction, with a clear message.
        assertThrows(IllegalArgumentException.class,
            () -> new Destination(Destination.Kind.HIGHWAY, 0, 0, null, 5));
    }

    @Test
    void anAcceptedRouteWithNoWaypointsIsRejectedAtConstruction() {
        // Route.of(List.of()) produced an accepted, empty route that nobody would know how to read.
        assertThrows(IllegalArgumentException.class, () -> Route.of(List.of()));
    }

    @Test
    void whatIsLeftToFlyFromWhereYouAreToTheEnd() {
        List<Waypoint> route = List.of(new Waypoint(0, 0), new Waypoint(300, 400), new Waypoint(300, 1000));
        assertEquals(1100.0, RoutePlanner.remainingBlocks(route, 0, new Waypoint(0, 0)), 1e-9);
        assertEquals(1100.0, RoutePlanner.remainingBlocks(route, 1, new Waypoint(0, 0)), 1e-9);
        assertEquals(600.0, RoutePlanner.remainingBlocks(route, 2, new Waypoint(300, 400)), 1e-9);
        assertThrows(IndexOutOfBoundsException.class, () -> RoutePlanner.remainingBlocks(route, 3, new Waypoint(0, 0)));
        assertThrows(IndexOutOfBoundsException.class, () -> RoutePlanner.remainingBlocks(route, -1, new Waypoint(0, 0)));
        assertThrows(NullPointerException.class, () -> RoutePlanner.remainingBlocks(route, 0, null));
    }
}
