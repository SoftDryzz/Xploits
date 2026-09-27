package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xploits.pvp.crystal.core.MovementReach.JUMP_HEIGHT;
import static com.xploits.pvp.crystal.core.MovementReach.Offset;
import static com.xploits.pvp.crystal.core.MovementReach.RING_POINTS;
import static com.xploits.pvp.crystal.core.MovementReach.offsets;
import static com.xploits.pvp.crystal.core.MovementReach.worstRawDamage;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task R3-16: the worst-case self damage reads every position you could reach before a crystal explodes,
 * never only where you stand right now. Every number here is exact in binary, so each boundary is the real
 * one. Fix round 1 (review-r3-16.md): the ring and the projection are checked at both your current height and
 * a jump's, and the class never throws on any input.
 */
class MovementReachTest {
    private static final double EPS = 1e-9;
    /** With a nonzero speed and landing ticks: 1 current + 1 projected + RING_POINTS ring points, per height. */
    private static final int MOVING_BLOCK_SIZE = 2 + RING_POINTS;

    // offsets()

    @Test
    void standingStillGivesTheFourFixedPoints() {
        List<Offset> points = offsets(0, 0, 5);
        assertEquals(4, points.size(), "current and projected (same point), at both heights; no ring at radius 0");
        assertEquals(new Offset(0, 0, 0), points.get(0));
        assertEquals(new Offset(0, 0, 0), points.get(1));
        assertEquals(new Offset(0, JUMP_HEIGHT, 0), points.get(2));
        assertEquals(new Offset(0, JUMP_HEIGHT, 0), points.get(3));
    }

    @Test
    void zeroLandingTicksCollapsesEvenAMovingPlayerToTheFixedPoints() {
        List<Offset> points = offsets(2, 3, 0);
        assertEquals(4, points.size());
        assertEquals(new Offset(0, 0, 0), points.get(1), "projected 0 ticks away is still here");
        assertEquals(new Offset(0, JUMP_HEIGHT, 0), points.get(3), "same, at jump height");
    }

    @Test
    void movingProjectsForwardByVelocityTimesLandingTicksAtBothHeights() {
        List<Offset> points = offsets(1.5, -0.5, 4);
        assertEquals(new Offset(6.0, 0, -2.0), points.get(1));
        assertEquals(new Offset(6.0, JUMP_HEIGHT, -2.0), points.get(MOVING_BLOCK_SIZE + 1));
    }

    @Test
    void theRingHasTheDocumentedNumberOfPointsAtSpeedTimesLandingTicksAtBothHeights() {
        List<Offset> points = offsets(1, 0, 4);
        // (current, projected, RING_POINTS ring points) at each of the two heights.
        assertEquals(2 * MOVING_BLOCK_SIZE, points.size());
        double radius = 1.0 * 4;
        for (double h : new double[] {0, JUMP_HEIGHT}) {
            int base = h == 0 ? 0 : MOVING_BLOCK_SIZE;
            // Ring points start right after the projected point, index 2 in each height's block.
            assertEquals(radius, points.get(base + 2).dx(), EPS, "height " + h);
            assertEquals(0.0, points.get(base + 2).dz(), EPS, "height " + h);
            for (int i = 0; i < RING_POINTS; i++) {
                Offset o = points.get(base + 2 + i);
                assertEquals(radius, Math.hypot(o.dx(), o.dz()), EPS, "height " + h + " ring point " + i);
                assertEquals(h, o.dy(), "ring point " + i + " stays on its own height's horizontal plane");
            }
        }
    }

    @Test
    void everyPointInTheFirstBlockIsAtGroundHeightAndTheSecondAtJumpHeight() {
        List<Offset> points = offsets(3, 4, 6);
        for (int i = 0; i < MOVING_BLOCK_SIZE; i++) assertEquals(0.0, points.get(i).dy(), "point " + i);
        for (int i = MOVING_BLOCK_SIZE; i < 2 * MOVING_BLOCK_SIZE; i++) {
            assertEquals(JUMP_HEIGHT, points.get(i).dy(), "point " + i);
        }
    }

    @Test
    void negativeLandingTicksIsTreatedAsZeroNotRejected() {
        assertEquals(offsets(1, 1, 0), offsets(1, 1, -5));
    }

    @Test
    void nonFiniteVelocityIsTreatedAsZeroNotRejected() {
        assertDoesNotThrow(() -> offsets(Double.NaN, Double.POSITIVE_INFINITY, 5));
        assertEquals(offsets(0, 0, 5), offsets(Double.NaN, Double.POSITIVE_INFINITY, 5));
        assertEquals(offsets(0, 0, 5), offsets(Double.NEGATIVE_INFINITY, Double.NaN, 5));
    }

    // worstRawDamage()

    @Test
    void standingStillTheWorstIsTheCloserOfCurrentOrTheJumpPoint() {
        // A crystal 4 blocks away, dead ahead and level: jumping (feet up 1.25) only moves us further from it,
        // so standing where we are is the worst (and only) meaningful point.
        float worst = worstRawDamage(4, 0, 0, 0, 0, 5);
        assertEquals(ExplosionMath.rawDamage(4.0, 1.0), worst);
    }

    @Test
    void jumpingCanBeWorseThanStandingWhenTheCrystalIsAbove() {
        // A crystal 1 block above our feet, otherwise on top of us: standing gives distance 1, jumping to
        // JUMP_HEIGHT gives distance |1 - JUMP_HEIGHT| = 0.25, which is closer and so deals more.
        float worst = worstRawDamage(0, 1, 0, 0, 0, 5);
        double jumpDistance = Math.abs(1 - JUMP_HEIGHT);
        assertEquals(ExplosionMath.rawDamage(jumpDistance, 1.0), worst);
        assertTrue(worst > ExplosionMath.rawDamage(1.0, 1.0));
    }

    @Test
    void movingTowardTheCrystalRaisesTheWorstCaseOverStandingStill() {
        // Crystal 6 blocks ahead on +x; moving toward it at 1 block/tick over 4 ticks closes to 2 blocks.
        float worst = worstRawDamage(6, 0, 0, 1, 0, 4);
        assertEquals(ExplosionMath.rawDamage(2.0, 1.0), worst, 1e-6);
        assertTrue(worst > ExplosionMath.rawDamage(6.0, 1.0));
    }

    @Test
    void theRingCoversATurnTheVelocityAloneWouldMiss() {
        // Crystal 6 blocks away on +z (perpendicular to our +x velocity): the straight-line projection along
        // +x never gets closer to it (distance stays hypot(radius, 6) > 6), but a ring point toward +z does
        // (distance 6 - radius).
        double speed = 1.0;
        long ticks = 4;
        double radius = speed * ticks;
        float worst = worstRawDamage(0, 0, 6, speed, 0, ticks);
        assertEquals(ExplosionMath.rawDamage(6 - radius, 1.0), worst, 1e-6);
        assertTrue(worst > ExplosionMath.rawDamage(6.0, 1.0));
        assertTrue(Math.hypot(radius, 6) > 6, "the projection alone would not have found this");
    }

    @Test
    void theRingAndTheJumpCombineForACrystalNeitherAloneWouldFind() {
        // Fix round 1 (review-r3-16.md Important #2): a crystal both off to the side (only the ring toward +z
        // reaches it, our velocity is along +x) AND 1 block up. Neither "ring at ground height" nor "jump in
        // place" alone is the true worst case; only riding the ring while also at jump height is.
        double speed = 1.0;
        long ticks = 4;
        double radius = speed * ticks;
        float worst = worstRawDamage(0, 1, 6, speed, 0, ticks);

        double combined = Math.hypot(1 - JUMP_HEIGHT, 6 - radius);
        double ringOnlyAtGround = Math.hypot(1, 6 - radius);
        double jumpOnlyInPlace = Math.hypot(1 - JUMP_HEIGHT, 6);
        assertEquals(ExplosionMath.rawDamage(combined, 1.0), worst, 1e-6);
        assertTrue(worst > ExplosionMath.rawDamage(ringOnlyAtGround, 1.0), "the ring alone, at ground height, understates it");
        assertTrue(worst > ExplosionMath.rawDamage(jumpOnlyInPlace, 1.0), "jumping in place alone understates it");
    }

    @Test
    void beyondReachTheWorstIsStillZero() {
        assertEquals(0f, worstRawDamage(50, 0, 0, 0, 0, 5));
    }

    @Test
    void worstRawDamageNeverThrowsOnNonFiniteOrNegativeInput() {
        assertDoesNotThrow(() -> worstRawDamage(4, 0, 0, Double.NaN, Double.POSITIVE_INFINITY, -5));
        // Non-finite/negative velocity and landing ticks are all treated as "not moving": the same result as
        // the explicit zero-velocity, zero-ticks call.
        assertEquals(worstRawDamage(4, 0, 0, 0, 0, 0), worstRawDamage(4, 0, 0, Double.NaN, Double.POSITIVE_INFINITY, -5));
    }
}
