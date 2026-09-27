package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path of self-circle (R3-14): the offset from the centre of a circle of radius {@link CircleWalk#RADIUS}
 * walked at {@link CircleWalk#SPEED} blocks/s, counter-clockwise seen from above, starting at the point nearest
 * {@code +x} (mirrors {@code Circler}'s own constants and formula, kept independent so the pure core stays free
 * of anything outside {@code bench.core}). Pure and deterministic: the same tick always gives the same offset.
 */
class CircleWalkTest {
    private static final double EPS = 1e-9;

    @Test
    void atTickZeroItIsOnTheNearPointAtDistanceMinusRadius() {
        // angle(0) = pi: cos(pi) = -1, sin(pi) = 0 (within floating error).
        assertEquals(-CircleWalk.RADIUS, CircleWalk.offsetX(0), 1e-6);
        assertEquals(0, CircleWalk.offsetZ(0), 1e-6);
    }

    @Test
    void everyOffsetStaysExactlyOnTheCircle() {
        for (int k = 0; k <= 20 * 30; k++) {
            double x = CircleWalk.offsetX(k);
            double z = CircleWalk.offsetZ(k);
            double radius = Math.sqrt(x * x + z * z);
            assertEquals(CircleWalk.RADIUS, radius, 1e-9, "tick " + k + " is off the circle");
        }
    }

    @Test
    void itTurnsCounterClockwiseSeenFromAbove() {
        // A growing k turns +x toward -z first (angle grows past pi), matching Circler's own convention.
        double z0 = CircleWalk.offsetZ(0);
        double z1 = CircleWalk.offsetZ(1);
        assertTrue(z1 > z0, "z should grow as the walk starts (turning toward -z from +x is a growing angle)");
    }

    @Test
    void oneLapTakesRadiusTimesTwoPiOverSpeedSeconds() {
        // 2 * pi * r / speed seconds, in ticks (20 a second): back near the start point, within the rounding
        // to a whole tick (at most half a tick's worth of angle, i.e. half of speed / radius / 20 rad).
        double lapSeconds = 2 * Math.PI * CircleWalk.RADIUS / CircleWalk.SPEED;
        int lapTicks = (int) Math.round(lapSeconds * 20);
        double maxDrift = CircleWalk.RADIUS * (CircleWalk.SPEED / CircleWalk.RADIUS / 20) / 2 + 1e-9;
        assertEquals(CircleWalk.offsetX(0), CircleWalk.offsetX(lapTicks), maxDrift);
        assertEquals(CircleWalk.offsetZ(0), CircleWalk.offsetZ(lapTicks), maxDrift);
    }

    @Test
    void jumpHeightIsZeroBeforeTheFirstJump() {
        for (int k = 0; k < CircleWalk.FIRST_JUMP; k++) {
            assertEquals(0, CircleWalk.jumpHeight(k), EPS, "tick " + k);
            assertFalse(CircleWalk.airborne(k), "tick " + k);
        }
    }

    @Test
    void jumpHeightIsAParabolaPeakingAtTheMiddleOfEachJump() {
        int start = CircleWalk.FIRST_JUMP;
        assertEquals(0, CircleWalk.jumpHeight(start), EPS);
        assertTrue(CircleWalk.airborne(start + 1));
        double mid = CircleWalk.jumpHeight(start + CircleWalk.JUMP_TICKS / 2);
        assertEquals(CircleWalk.JUMP_HEIGHT, mid, 1e-2);
        // Symmetric around the middle.
        assertEquals(CircleWalk.jumpHeight(start + 1), CircleWalk.jumpHeight(start + CircleWalk.JUMP_TICKS - 1), 1e-9);
        // Back at 0 and grounded once the jump ticks are used up.
        assertEquals(0, CircleWalk.jumpHeight(start + CircleWalk.JUMP_TICKS), EPS);
        assertFalse(CircleWalk.airborne(start + CircleWalk.JUMP_TICKS));
    }

    @Test
    void itJumpsAgainEveryJumpEveryTicks() {
        assertTrue(CircleWalk.airborne(CircleWalk.FIRST_JUMP + 1));
        assertFalse(CircleWalk.airborne(CircleWalk.FIRST_JUMP + CircleWalk.JUMP_TICKS));
        assertTrue(CircleWalk.airborne(CircleWalk.FIRST_JUMP + CircleWalk.JUMP_EVERY + 1));
    }

    @Test
    void aNegativeTickHoldsTheSameAsTickZero() {
        assertEquals(CircleWalk.offsetX(0), CircleWalk.offsetX(-5), EPS);
        assertEquals(CircleWalk.offsetZ(0), CircleWalk.offsetZ(-5), EPS);
        assertEquals(0, CircleWalk.jumpHeight(-5), EPS);
        assertFalse(CircleWalk.airborne(-5));
    }

    @Test
    void theSameTickAlwaysGivesTheSameOffset() {
        assertEquals(CircleWalk.offsetX(37), CircleWalk.offsetX(37), 0);
        assertEquals(CircleWalk.offsetZ(37), CircleWalk.offsetZ(37), 0);
        assertEquals(CircleWalk.jumpHeight(37), CircleWalk.jumpHeight(37), 0);
    }
}
