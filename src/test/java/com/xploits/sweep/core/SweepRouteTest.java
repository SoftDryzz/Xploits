package com.xploits.sweep.core;

import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepRouteTest {
    private static final double EPSILON = 1e-9;

    /**
     * Two lanes of 100 blocks 50 apart, in alternating directions as the planner emits them. The
     * vertices come out as (0,0) (100,0) (100,50) (0,50), so the three gaps measure 100, 50 and 100:
     * the sweep is 250 blocks computed by hand, not with the class's formula.
     */
    private static List<Lane> twoLanes() {
        return List.of(new Lane(0, 0, 100, 0), new Lane(100, 50, 0, 50));
    }

    // ---------------------------------------------------------------------------------------
    // The vertices
    // ---------------------------------------------------------------------------------------

    @Test
    void eachLaneContributesItsStartAndEndInThatOrder() {
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertEquals(4, route.size());
        assertEquals(List.of(
            new Waypoint(0, 0),
            new Waypoint(100, 0),
            new Waypoint(100, 50),
            new Waypoint(0, 50)), route.waypoints());
    }

    // ---------------------------------------------------------------------------------------
    // The three legs of the trip
    // ---------------------------------------------------------------------------------------

    @Test
    void theThreeLegsEachComeOutWithTheirOwnNumber() {
        // Taking off at (0,-30): 30 blocks to the start of the first lane, 250 of sweep
        // (100 + 50 + 100) and 80 back from (0,50) to (0,-30). Total 360.
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertEquals(30, route.approachBlocks(), EPSILON);
        assertEquals(250, route.sweepBlocks(), EPSILON);
        assertEquals(80, route.returnBlocks(), EPSILON);
        assertEquals(360, route.totalBlocks(), EPSILON);
    }

    @Test
    void withoutTheReturnTheTripIsJustApproachAndSweep() {
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), false);

        assertEquals(0, route.returnBlocks(), EPSILON);
        assertEquals(280, route.totalBlocks(), EPSILON);
        assertEquals(250, route.sweepBlocks(), EPSILON);
    }

    @Test
    void theApproachIsEuclideanNotPerAxis() {
        // Taking off at (-30,-40), the start of the first lane is at (0,0): 50 blocks by the 3-4-5
        // triangle, not the 70 that adding both axes would give.
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(-30, -40), false);

        assertEquals(50, route.approachBlocks(), EPSILON);
    }

    /**
     * The regression test for the bug that slipped in twice: budgeting the trip with the plan's
     * "total". That number is only the sweep, and the difference from the real trip is exactly the
     * approach and the return.
     */
    @Test
    void theTripIsStrictlyLongerThanThePlanTotalAndTheDifferenceIsApproachPlusReturn() {
        SweepPlanner.SweepPlan plan = SweepPlanner.SweepPlan.of(twoLanes());
        SweepRoute route = SweepRoute.of(plan.lanes(), new Waypoint(0, -30), true);

        assertEquals(250, plan.totalBlocks(), EPSILON);
        assertTrue(route.totalBlocks() > plan.totalBlocks());
        assertEquals(110, route.totalBlocks() - plan.totalBlocks(), EPSILON);
    }

    /**
     * The two paths to the same number, on a real plan from the planner:
     * {@code SweepPlan.totalBlocks()} adds lane lengths and the hops between them, and
     * {@code sweepBlocks()} adds distances between consecutive vertices. If they ever stop matching,
     * one of the two has broken.
     */
    @Test
    void theRoutesSweepMatchesThePlannersPlanTotal() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 23);
        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 8);
        assertTrue(plan.lanes().size() > 1, "the case is only worth anything if there are links between lanes");

        SweepRoute route = SweepRoute.of(plan.lanes(), new Waypoint(-5_000, -5_000), true);

        assertEquals(plan.totalBlocks(), route.sweepBlocks(), EPSILON);
    }

    // ---------------------------------------------------------------------------------------
    // What is left to fly: it is what feeds the firework projection
    // ---------------------------------------------------------------------------------------

    @Test
    void whatRemainsFromEachVertexIsCountedBackwardsIncludingTheReturn() {
        // From (0,50), the last vertex, only the return is left: 80. From (100,50), 100 + 80.
        // From (100,0), 50 + 100 + 80. From (0,0), 100 + 50 + 100 + 80.
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertEquals(330, route.remainingFrom(0), EPSILON);
        assertEquals(230, route.remainingFrom(1), EPSILON);
        assertEquals(180, route.remainingFrom(2), EPSILON);
        assertEquals(80, route.remainingFrom(3), EPSILON);
    }

    @Test
    void withoutAReturnTheLastVertexLeavesNothingToFly() {
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), false);

        assertEquals(250, route.remainingFrom(0), EPSILON);
        assertEquals(0, route.remainingFrom(3), EPSILON);
    }

    @Test
    void whatRemainsForThePlayerAddsTheDistanceFromWhereHeIsToTheVertexHeIsHeadingFor() {
        // Halfway along the link between the two lanes, at (100,20): 30 to (100,50) and 180 from there.
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertEquals(210, route.remainingFrom(2, new Waypoint(100, 20)), EPSILON);
    }

    @Test
    void aVertexThatDoesNotExistReturnsNoMadeUpDistance() {
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertThrows(IndexOutOfBoundsException.class, () -> route.remainingFrom(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> route.remainingFrom(4));
        assertThrows(IndexOutOfBoundsException.class, () -> route.remainingFrom(4, new Waypoint(0, 0)));
    }

    // ---------------------------------------------------------------------------------------
    // The shortest gap: whether any lane gets consumed without being flown depends on it
    // ---------------------------------------------------------------------------------------

    @Test
    void theTightestGapIsTheLinkBetweenLanesWhenItIsShorterThanTheLanes() {
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertEquals(50, route.tightestGap(), EPSILON);
    }

    @Test
    void theTightestGapCanBeALaneIfTheLanesAreShorterThanTheLink() {
        // Lanes of 40 with 300 between them: the short gap is the lane, not the link.
        List<Lane> shortLanes = List.of(new Lane(0, 0, 40, 0), new Lane(40, 300, 0, 300));
        SweepRoute route = SweepRoute.of(shortLanes, new Waypoint(0, 0), false);

        assertEquals(40, route.tightestGap(), EPSILON);
    }

    @Test
    void aSingleLaneRouteHasOneGapWhichIsTheLaneItself() {
        SweepRoute route = SweepRoute.of(List.of(new Lane(0, 0, 400, 0)), new Waypoint(0, 0), true);

        assertEquals(2, route.size());
        assertEquals(0, route.approachBlocks(), EPSILON);
        assertEquals(400, route.sweepBlocks(), EPSILON);
        assertEquals(400, route.returnBlocks(), EPSILON);
        assertEquals(800, route.totalBlocks(), EPSILON);
        assertEquals(400, route.tightestGap(), EPSILON);
    }

    // ---------------------------------------------------------------------------------------
    // What is not built
    // ---------------------------------------------------------------------------------------

    @Test
    void aPlanWithoutLanesIsNotAShortRoute() {
        // An accepted plan without lanes means the area is already fully seen. Returning an empty
        // route would let someone take off towards nowhere.
        assertThrows(IllegalArgumentException.class,
            () -> SweepRoute.of(List.of(), new Waypoint(0, 0), true));
    }

    @Test
    void withoutKnowingTheTakeoffPointThereIsNoTripToBudget() {
        assertThrows(NullPointerException.class, () -> SweepRoute.of(twoLanes(), null, true));
        assertThrows(NullPointerException.class, () -> SweepRoute.of(null, new Waypoint(0, 0), true));
    }

    // ---------------------------------------------------------------------------------------
    // minimumGap(): the sweep's spacing rule, which is not the one of the evasion routes
    // ---------------------------------------------------------------------------------------

    @Test
    void aSweepsMinimumSpacingIsTheMarginNotTwiceIt() {
        // In a sweep the vertices form a right angle: the one that ends a lane is reached along it
        // and the next one is perpendicular, so the distance to the second is the hypotenuse and
        // never drops below the gap. Twice the margin protects against two aligned vertices, which
        // is the geometry of the evasion routes, not that of a lawnmower.
        assertEquals(150, SweepRoute.minimumGap(150), EPSILON);
        assertEquals(100, SweepRoute.minimumGap(100), EPSILON);
    }

    @Test
    void theShortestLaneAndTheShortestLinkAreDistinctGaps() {
        // The vertices come in pairs: gap 0->1 is a lane, 1->2 the link to the next. With
        // twoLanes() the lanes measure 100 and the link 50.
        SweepRoute route = SweepRoute.of(twoLanes(), new Waypoint(0, -30), true);

        assertEquals(100, route.shortestLane(), EPSILON);
        assertEquals(50, route.shortestLink(), EPSILON);
        assertEquals(50, route.tightestGap(), EPSILON);
    }

    @Test
    void aSingleLaneRouteHasNoLink() {
        // And then the shortest link is not zero -which would look like an impossible link- but
        // there is none at all: whoever warns about short links has nothing to warn about.
        SweepRoute route = SweepRoute.of(List.of(new Lane(0, 0, 400, 0)), new Waypoint(0, 0), false);

        assertEquals(400, route.shortestLane(), EPSILON);
        assertEquals(Double.MAX_VALUE, route.shortestLink(), EPSILON);
    }

    @Test
    void aServerThatSendsEightChunksCanBeSweptWithFactoryDefaults() {
        // The whole failure, with numbers: observed radius 8 -normal on a busy anarchy- and the
        // factory width margin give a width of 12 chunks, that is links between lanes of less than
        // 300 blocks. Against the floor of the evasion routes THAT sweep was always rejected, for
        // any rectangle, and none of the ways out the rejection offered worked.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 16, 0);
        }
        int width = probe.laneWidthInChunks(0.2);
        assertEquals(12, width);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(new SweepArea(0, 0, 199, 199),
            Coverage.empty(), width);
        SweepRoute route = SweepRoute.of(plan.lanes(), new Waypoint(0, 0), true);

        assertTrue(route.shortestLane() > SweepRoute.minimumGap(RoutePlanner.DEFAULT_WAYPOINT_MARGIN),
            "no lane is lost, which is the only thing that cannot be allowed");
        assertTrue(route.tightestGap() < RoutePlanner.minimumSpacing(RoutePlanner.DEFAULT_WAYPOINT_MARGIN),
            "and the shortest gap is still below the floor of the evasion routes: that is the case"
                + " that was always rejected");
    }

    @Test
    void theNarrowLastBandJoinsItsLaneToThePreviousOneWithoutLosingAny() {
        // An area that is not a multiple of the width leaves a narrower last band, its lane is
        // centred closer to the previous one and the link ends up little more than half a width.
        // That is a short link -a lost corner, a warning-, never a lost lane.
        SweepPlanner.SweepPlan plan = SweepPlanner.plan(new SweepArea(0, 0, 199, 192),
            Coverage.empty(), 12);
        SweepRoute route = SweepRoute.of(plan.lanes(), new Waypoint(0, 0), false);

        assertEquals(104, route.shortestLink(), EPSILON);
        assertTrue(route.shortestLane() > SweepRoute.minimumGap(RoutePlanner.DEFAULT_WAYPOINT_MARGIN));
    }

    @Test
    void anAreaTinyAlongItsLongSideDoesEndUpWithoutAFlyableLane() {
        // This is what does have to be rejected: an area 8 chunks along its long side gives lanes of
        // 112 blocks, which fit inside the factory margin. That lane would be consumed without being
        // flown and the sweep would count it as combed anyway, which is the lie of spec §9.
        SweepPlanner.SweepPlan plan = SweepPlanner.plan(new SweepArea(0, 0, 7, 7), Coverage.empty(), 12);
        SweepRoute route = SweepRoute.of(plan.lanes(), new Waypoint(0, 0), false);

        assertEquals(112, route.shortestLane(), EPSILON);
        assertTrue(route.shortestLane() <= SweepRoute.minimumGap(RoutePlanner.DEFAULT_WAYPOINT_MARGIN));
    }
}
