package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xploits.pvp.crystal.core.MovementReach.JUMP_HEIGHT;
import static com.xploits.pvp.crystal.core.MovementReach.Offset;
import static com.xploits.pvp.crystal.core.MovementReach.RING_POINTS;
import static com.xploits.pvp.crystal.core.MovementReach.offsets;
import static com.xploits.pvp.crystal.core.MovementReach.worstRawDamage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task R3-16: the worst-case self damage reads every position you could reach before a crystal explodes,
 * never only where you stand right now. Every number here is exact in binary, so each boundary is the real
 * one.
 */
class MovementReachTest {
    private static final double EPS = 1e-9;

    // offsets()

    @Test
    void standingStillGivesJustTheThreeFixedPoints() {
        List<Offset> points = offsets(0, 0, 5);
        assertEquals(3, points.size(), "current, projected (same point) and the jump point; no ring at radius 0");
        assertEquals(new Offset(0, 0, 0), points.get(0));
        assertEquals(new Offset(0, 0, 0), points.get(1));
        assertEquals(new Offset(0, JUMP_HEIGHT, 0), points.get(2));
    }

    @Test
    void zeroLandingTicksCollapsesEvenAMovingPlayerToTheFixedPoints() {
        List<Offset> points = offsets(2, 3, 0);
        assertEquals(3, points.size());
        assertEquals(new Offset(0, 0, 0), points.get(1), "projected 0 ticks away is still here");
    }

    @Test
    void movingProjectsForwardByVelocityTimesLandingTicks() {
        List<Offset> points = offsets(1.5, -0.5, 4);
        assertEquals(new Offset(6.0, 0, -2.0), points.get(1));
    }

    @Test
    void theRingHasTheDocumentedNumberOfPointsAtSpeedTimesLandingTicks() {
        List<Offset> points = offsets(1, 0, 4);
        // current, projected, RING_POINTS ring points, jump.
        assertEquals(3 + RING_POINTS, points.size());
        double radius = 1.0 * 4;
        // Ring points start right after the projected point, index 2.
        assertEquals(radius, points.get(2).dx(), EPS);
        assertEquals(0.0, points.get(2).dz(), EPS);
        // Evenly spaced: every ring point sits exactly on the circle of that radius.
        for (int i = 0; i < RING_POINTS; i++) {
            Offset o = points.get(2 + i);
            assertEquals(radius, Math.hypot(o.dx(), o.dz()), EPS, "ring point " + i);
            assertEquals(0.0, o.dy(), "the ring is horizontal");
        }
    }

    @Test
    void theJumpPointIsAlwaysLastAndOnlyVertical() {
        List<Offset> points = offsets(3, 4, 6);
        Offset jump = points.get(points.size() - 1);
        assertEquals(new Offset(0, JUMP_HEIGHT, 0), jump);
    }

    @Test
    void negativeLandingTicksIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> offsets(0, 0, -1));
    }

    @Test
    void nonFiniteVelocityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> offsets(Double.NaN, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> offsets(0, Double.POSITIVE_INFINITY, 5));
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
    void beyondReachTheWorstIsStillZero() {
        assertEquals(0f, worstRawDamage(50, 0, 0, 0, 0, 5));
    }
}
