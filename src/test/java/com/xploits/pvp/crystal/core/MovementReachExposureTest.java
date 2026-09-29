package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.MovementReach.Offset;
import com.xploits.pvp.crystal.core.MovementReach.Ranked;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B2: the worst case over the reach points at their REAL exposure, by branch and bound over the points
 * sorted worst-first by their exposure-1.0 raw damage. Exposure is faked here; the exact figure must never be
 * under-estimated.
 */
class MovementReachExposureTest {
    private static final float NONE = 0f;

    private static List<Ranked> moving() {
        return MovementReach.rankedByWorstRaw(5, 1, 0, 0.2, 0.1, 6);
    }

    /** The plain worst case with a fake exposure, checking every point (no pruning). */
    private static float bruteForce(List<Ranked> ranked, MovementReach.ExposureFunction e) {
        float best = 0f;
        for (Ranked r : ranked) best = Math.max(best, ExplosionMath.rawDamage(r.distance(), e.at(r.offset())));
        return best;
    }

    @Test
    void theOrderIsWorstFirstAndCoversTheDistinctOffsets() {
        List<Ranked> ranked = moving();
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).rawAtFullExposure() >= ranked.get(i).rawAtFullExposure());
        }
        assertEquals(MovementReach.offsets(0.2, 0.1, 6).stream().distinct().count(), ranked.size());
    }

    @Test
    void theFirstRankedValueIsTheOldExposureOneWorstCase() {
        assertEquals(MovementReach.worstRawDamage(5, 1, 0, 0.2, 0.1, 6), moving().get(0).rawAtFullExposure(), 0f);
    }

    @Test
    void standingStillCollapsesDuplicates() {
        assertEquals(2, MovementReach.rankedByWorstRaw(5, 0, 0, 0, 0, 4).size());
    }

    @Test
    void withFullExposureEverywhereItEqualsTheOldExposureOneBehaviour() {
        List<Ranked> ranked = moving();
        assertEquals(MovementReach.worstRawDamage(5, 1, 0, 0.2, 0.1, 6),
            MovementReach.worstRawDamage(ranked, o -> 1.0, NONE), 0f);
    }

    @Test
    void aShieldedJumpPointDoesNotCountAsFullyExposed() {
        // The explosion sits above the jump point, so the jump point is the worst at exposure 1.0; it is fully
        // behind cover (0) while standing is exposed at 0.25.
        List<Ranked> ranked = MovementReach.rankedByWorstRaw(0, 2.25, 4, 0, 0, 4);
        MovementReach.ExposureFunction cover = o -> o.dy() > 0 ? 0.0 : 0.25;
        float real = MovementReach.worstRawDamage(ranked, cover, NONE);
        assertEquals(bruteForce(ranked, cover), real, 0f);
        assertTrue(real < MovementReach.worstRawDamage(0, 2.25, 4, 0, 0, 4));
    }

    @Test
    void itStopsAsSoonAsTheNextCeilingIsNotAboveTheBest() {
        List<Ranked> ranked = moving();
        List<Offset> asked = new ArrayList<>();
        float result = MovementReach.worstRawDamage(ranked, o -> {
            asked.add(o);
            return 1.0;
        }, NONE);
        // Full exposure at the first point makes it the best; every later ceiling is <= it: exactly one ask.
        assertEquals(1, asked.size());
        assertEquals(ranked.get(0).rawAtFullExposure(), result, 0f);
    }

    @Test
    void itKeepsLookingWhileALaterCeilingCouldStillBeatTheBest() {
        List<Ranked> ranked = moving();
        List<Offset> asked = new ArrayList<>();
        // The worst-ceiling point is fully covered: the search must go on to the next one.
        MovementReach.worstRawDamage(ranked, o -> {
            asked.add(o);
            return o.equals(ranked.get(0).offset()) ? 0.0 : 1.0;
        }, NONE);
        assertTrue(asked.size() >= 2);
    }

    @Test
    void aFloorAtOrAboveTheCeilingAsksNothingAndKeepsTheFloor() {
        List<Ranked> ranked = moving();
        float floor = ranked.get(0).rawAtFullExposure();
        List<Offset> asked = new ArrayList<>();
        assertEquals(floor, MovementReach.worstRawDamage(ranked, o -> {
            asked.add(o);
            return 1.0;
        }, floor), 0f);
        assertEquals(0, asked.size());
    }

    @Test
    void theResultNeverDropsBelowTheFloor() {
        assertEquals(30f, MovementReach.worstRawDamage(moving(), o -> 0.0, 30f), 0f);
    }

    @Test
    void neverUnderEstimatesForAnyExposurePattern() {
        Random random = new Random(7);
        for (int trial = 0; trial < 400; trial++) {
            double ex = random.nextDouble() * 16 - 8;
            double ey = random.nextDouble() * 6 - 3;
            double ez = random.nextDouble() * 16 - 8;
            double vx = random.nextDouble() * 0.4 - 0.2;
            double vz = random.nextDouble() * 0.4 - 0.2;
            List<Ranked> ranked = MovementReach.rankedByWorstRaw(ex, ey, ez, vx, vz, 1 + random.nextInt(10));
            Map<Offset, Double> byOffset = new HashMap<>();
            for (Ranked r : ranked) byOffset.put(r.offset(), random.nextInt(5) / 4.0);
            MovementReach.ExposureFunction e = byOffset::get;
            assertEquals(bruteForce(ranked, e), MovementReach.worstRawDamage(ranked, e, NONE), 0f, "trial " + trial);
        }
    }

    @Test
    void anExposureThatIsNotANumberInZeroToOneReadsAsFull() {
        List<Ranked> ranked = moving();
        float full = MovementReach.worstRawDamage(ranked, o -> 1.0, NONE);
        for (double bad : new double[] {Double.NaN, -0.5, 1.5, Double.POSITIVE_INFINITY}) {
            assertEquals(full, MovementReach.worstRawDamage(ranked, o -> bad, NONE), 0f, "exposure " + bad);
        }
    }

    @Test
    void aNonFiniteExplosionStillPropagatesNaNAndNeverThrows() {
        List<Ranked> ranked = assertDoesNotThrow(() -> MovementReach.rankedByWorstRaw(Double.NaN, 0, 0, 1, 1, 3));
        assertTrue(Float.isNaN(MovementReach.worstRawDamage(ranked, o -> 0.0, NONE)));
        List<Offset> asked = new ArrayList<>();
        MovementReach.worstRawDamage(ranked, o -> {
            asked.add(o);
            return 0.0;
        }, NONE);
        assertEquals(0, asked.size(), "no raycast is spent on a point whose ceiling is already NaN");
        assertDoesNotThrow(() -> MovementReach.rankedByWorstRaw(1, 1, 1, Double.NaN, Double.POSITIVE_INFINITY, -4));
    }

    @Test
    void anExplosionBeyondTheRadiusFromEveryPointDealsNothingBeyondTheFloor() {
        List<Ranked> ranked = MovementReach.rankedByWorstRaw(100, 0, 0, 0.1, 0, 5);
        assertEquals(0f, MovementReach.worstRawDamage(ranked, o -> 1.0, NONE), 0f);
    }

    // Fix round 1: the points between the reach points

    @Test
    void midpointsAddTheHalfwayPointsTowardsEveryOffset() {
        List<Offset> reach = List.of(new Offset(4, 1.25, -2));
        List<Offset> all = MovementReach.withMidpoints(reach);
        assertTrue(all.contains(new Offset(4, 1.25, -2)));
        assertTrue(all.contains(new Offset(2, 1.25, -1)), "half way horizontally");
        assertTrue(all.contains(new Offset(4, 0.625, -2)), "half way up");
        assertTrue(all.contains(new Offset(2, 0.625, -1)), "both");
        assertEquals(4, all.size());
    }

    @Test
    void coverAtTheVerticesWithOpenGroundBetweenThemIsCaughtByTheMidpoint() {
        // The explosion is 5 blocks out. Every reach vertex (standing, the ring point out at 4) is covered
        // (exposure 0), but half way to the ring point, the ground is open (exposure 1).
        List<Offset> vertices = MovementReach.offsets(0.5, 0, 8);
        MovementReach.ExposureFunction openOnlyBetween = o ->
            Math.abs(o.dx() - 2.0) < 1e-9 && o.dy() == 0 ? 1.0 : 0.0;
        List<Ranked> plain = MovementReach.rankedByWorstRaw(5, 0, 0, vertices);
        List<Ranked> withMid = MovementReach.rankedByWorstRaw(5, 0, 0, MovementReach.withMidpoints(vertices));
        float withoutMidpoints = MovementReach.worstRawDamage(plain, openOnlyBetween, NONE);
        float withMidpoints = MovementReach.worstRawDamage(withMid, openOnlyBetween, NONE);
        assertTrue(withMidpoints > withoutMidpoints, "the open ground between the covered vertices is found");
        assertEquals(ExplosionMath.rawDamage(3.0, 1.0), withMidpoints, 0f);
    }

    @Test
    void midpointsNeverLowerTheResultAndStillPrune() {
        List<Offset> vertices = MovementReach.offsets(0.2, 0.1, 6);
        List<Ranked> plain = MovementReach.rankedByWorstRaw(5, 1, 0, vertices);
        List<Ranked> withMid = MovementReach.rankedByWorstRaw(5, 1, 0, MovementReach.withMidpoints(vertices));
        assertTrue(MovementReach.worstRawDamage(withMid, o -> 1.0, NONE) >= MovementReach.worstRawDamage(plain, o -> 1.0, NONE));
        List<Offset> asked = new ArrayList<>();
        MovementReach.worstRawDamage(withMid, o -> {
            asked.add(o);
            return 1.0;
        }, NONE);
        assertEquals(1, asked.size(), "at full exposure the worst ceiling ends the search at once");
    }

    // Task C2 (final review I3): the arcs between neighbouring ring points

    private static final double RADIUS = 2.0;

    private static Offset arc(int i, double h, double scale) {
        double angle = 2 * Math.PI * (i + 0.5) / MovementReach.RING_POINTS;
        return new Offset(scale * RADIUS * Math.cos(angle), h, scale * RADIUS * Math.sin(angle));
    }

    private static boolean near(Offset a, Offset b) {
        return Math.abs(a.dx() - b.dx()) < 1e-9 && Math.abs(a.dy() - b.dy()) < 1e-9 && Math.abs(a.dz() - b.dz()) < 1e-9;
    }

    @Test
    void theReachPointsIncludeAnArcMidpointBetweenEveryPairOfRingPointsAtBothHeights() {
        List<Offset> reach = MovementReach.reachPoints(0.5, 0, 4);
        for (double h : new double[] {0, MovementReach.JUMP_HEIGHT}) {
            for (int i = 0; i < MovementReach.RING_POINTS; i++) {
                Offset arc = arc(i, h, 1.0);
                assertTrue(reach.stream().anyMatch(o -> near(o, arc)), "arc " + i + " at " + h);
                Offset radial = arc(i, h, 0.5);
                assertTrue(reach.stream().anyMatch(o -> near(o, radial)), "radial midpoint of arc " + i + " at " + h);
            }
        }
        // Every one of the old reach points is still there.
        for (Offset o : MovementReach.withMidpoints(MovementReach.offsets(0.5, 0, 4))) assertTrue(reach.contains(o));
    }

    @Test
    void standingStillHasNoArcPoints() {
        assertEquals(MovementReach.withMidpoints(MovementReach.offsets(0, 0, 4)), MovementReach.reachPoints(0, 0, 4));
    }

    @Test
    void groundOpenOnlyOnAnArcBetweenTwoCoveredRingPointsIsCaught() {
        // Ring radius 2, so neighbouring ring points are 1.53 apart. Every other point is covered (0); only the
        // arc midpoint is open, and the explosion sits one block beyond it, in line with it.
        for (double h : new double[] {0, MovementReach.JUMP_HEIGHT}) {
            for (int i = 0; i < MovementReach.RING_POINTS; i++) {
                Offset open = arc(i, h, 1.0);
                Offset beyond = arc(i, h, 1.5);
                MovementReach.ExposureFunction openOnlyThere = o -> near(o, open) ? 1.0 : 0.0;
                float withArcs = MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(beyond.dx(), beyond.dy(),
                    beyond.dz(), MovementReach.reachPoints(0.5, 0, 4)), openOnlyThere, NONE);
                float without = MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(beyond.dx(), beyond.dy(),
                    beyond.dz(), MovementReach.withMidpoints(MovementReach.offsets(0.5, 0, 4))), openOnlyThere, NONE);
                assertEquals(ExplosionMath.rawDamage(1.0, 1.0), withArcs, 1e-4f, "arc " + i + " at " + h);
                assertTrue(withArcs > without, "the old reach points missed arc " + i + " at " + h);
            }
        }
    }

    @Test
    void groundOpenOnlyHalfWayToAnArcMidpointIsCaughtToo() {
        Offset open = arc(3, 0, 0.5);
        Offset beyond = arc(3, 0, 1.0);
        float real = MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(beyond.dx(), beyond.dy(), beyond.dz(),
            MovementReach.reachPoints(0.5, 0, 4)), o -> near(o, open) ? 1.0 : 0.0, NONE);
        assertEquals(ExplosionMath.rawDamage(1.0, 1.0), real, 1e-4f);
    }

    @Test
    void theArcPointsNeverLowerTheResultAndStillPrune() {
        List<Offset> old = MovementReach.withMidpoints(MovementReach.offsets(0.2, 0.1, 6));
        List<Offset> all = MovementReach.reachPoints(0.2, 0.1, 6);
        assertTrue(all.containsAll(old));
        assertTrue(MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(5, 1, 0, all), o -> 1.0, NONE)
            >= MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(5, 1, 0, old), o -> 1.0, NONE));
        List<Offset> asked = new ArrayList<>();
        MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(5, 1, 0, all), o -> {
            asked.add(o);
            return 1.0;
        }, NONE);
        assertEquals(1, asked.size());
        assertTrue(all.size() <= 4 * 2 * (2 + 2 * MovementReach.RING_POINTS), "bounded: " + all.size());
    }

    @Test
    void nonFiniteVelocityAndNegativeTicksStillNeverThrow() {
        assertDoesNotThrow(() -> MovementReach.reachPoints(Double.NaN, Double.POSITIVE_INFINITY, -5));
    }
}
