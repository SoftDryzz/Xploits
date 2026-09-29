package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.MovementReach.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task B3: the measured displacement and the two things the ring now takes from it (radius, heights). */
class MovementHistoryTest {
    private static final double EPS = 1e-9;

    // MovementHistory

    @Test
    void nothingMeasuredYetIsEmptyForAnyPositiveWindow() {
        MovementHistory h = new MovementHistory();
        assertFalse(h.maxDisplacement(1).isPresent());
        h.record(1, 0);
        h.record(1, 0);
        assertFalse(h.maxDisplacement(3).isPresent(), "history shorter than the window keeps the caller's assumption");
        assertEquals(2.0, h.maxDisplacement(2).getAsDouble(), EPS);
    }

    @Test
    void aWindowOfZeroOrLessIsADistanceOfZero() {
        assertEquals(0.0, new MovementHistory().maxDisplacement(0).getAsDouble(), 0.0);
        assertEquals(0.0, new MovementHistory().maxDisplacement(-3).getAsDouble(), 0.0);
    }

    @Test
    void aStraightLineCoversTheSumOfItsTicks() {
        MovementHistory h = new MovementHistory();
        for (int i = 0; i < 10; i++) h.record(0.2, 0);
        assertEquals(1.0, h.maxDisplacement(5).getAsDouble(), EPS);
        assertEquals(2.0, h.maxDisplacement(10).getAsDouble(), EPS);
    }

    @Test
    void aBurstInTheMiddleIsFoundWhereverItSits() {
        MovementHistory h = new MovementHistory();
        for (int i = 0; i < 6; i++) h.record(0, 0);
        for (int i = 0; i < 3; i++) h.record(0, 1);
        for (int i = 0; i < 6; i++) h.record(0, 0);
        assertEquals(3.0, h.maxDisplacement(3).getAsDouble(), EPS);
    }

    @Test
    void aPathThatCurvesBackIsMeasuredAtTheFarthestPointNotOnlyAtTheEnd() {
        // 5 ticks out, 5 ticks back: after the whole 10 ticks you are back where you began, but a crystal that
        // explodes after 5 finds you 5 blocks away.
        MovementHistory h = new MovementHistory();
        for (int i = 0; i < 5; i++) h.record(1, 0);
        for (int i = 0; i < 5; i++) h.record(-1, 0);
        assertEquals(5.0, h.maxDisplacement(10).getAsDouble(), EPS);
    }

    @Test
    void onlyTheLast40PreTicksAreKept() {
        MovementHistory h = new MovementHistory();
        for (int i = 0; i < 5; i++) h.record(10, 0);
        for (int i = 0; i < MovementHistory.CAPACITY; i++) h.record(0, 0);
        assertEquals(MovementHistory.CAPACITY, h.size());
        assertEquals(0.0, h.maxDisplacement(5).getAsDouble(), EPS, "the burst has scrolled out");
    }

    @Test
    void aWindowLongerThanTheHistoryCanHoldIsNeverMeasured() {
        MovementHistory h = new MovementHistory();
        for (int i = 0; i < 60; i++) h.record(0.1, 0);
        assertFalse(h.maxDisplacement(MovementHistory.CAPACITY + 1).isPresent());
        assertTrue(h.maxDisplacement(MovementHistory.CAPACITY).isPresent());
    }

    @Test
    void nonFiniteInputIsRecordedAsNoMovementAndNeverThrows() {
        MovementHistory h = new MovementHistory();
        h.record(Double.NaN, 5);
        h.record(3, Double.POSITIVE_INFINITY);
        h.record(1, 0);
        assertEquals(1.0, h.maxDisplacement(3).getAsDouble(), EPS);
    }

    @Test
    void resetForgetsEverything() {
        MovementHistory h = new MovementHistory();
        for (int i = 0; i < 5; i++) h.record(1, 1);
        h.reset();
        assertEquals(0, h.size());
        assertFalse(h.maxDisplacement(1).isPresent());
    }

    // MovementReach radius and heights

    private static double maxHorizontal(List<Offset> offsets) {
        double max = 0;
        for (Offset o : offsets) max = Math.max(max, Math.hypot(o.dx(), o.dz()));
        return max;
    }

    @Test
    void theRingIsNeverBelowWhatWeReallyMoved() {
        // Standing still now (speed 0) but 6 blocks really covered lately: the ring must reach 6.
        List<Offset> o = MovementReach.offsets(0, 0, 5, 6.0, new double[] {0});
        assertEquals(6.0, maxHorizontal(o), EPS);
        assertEquals(2 + MovementReach.RING_POINTS, o.size());
    }

    @Test
    void theRingIsNeverBelowSpeedTimesLandingTicks() {
        List<Offset> o = MovementReach.offsets(0.5, 0, 4, 1.0, new double[] {0});
        assertEquals(2.0, maxHorizontal(o), EPS, "0.5 * 4 beats the measured 1.0");
    }

    @Test
    void anUnmeasuredOrBrokenMeasureChangesNothing() {
        List<Offset> base = MovementReach.offsets(0.5, 0.25, 4);
        for (double bad : new double[] {0, -3, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(base, MovementReach.offsets(0.5, 0.25, 4, bad, new double[] {0, MovementReach.JUMP_HEIGHT}));
        }
    }

    @Test
    void theJumpPointIsOnlyThereOnTheGround() {
        assertArrayEquals(new double[] {0, MovementReach.JUMP_HEIGHT}, MovementReach.heights(true, 0.3, 10), 0.0);
        double[] air = MovementReach.heights(false, -0.5, 10);
        for (double h : air) assertTrue(h != MovementReach.JUMP_HEIGHT);
    }

    @Test
    void inTheAirTheReachFollowsTheFlightAndNeverUnderShootsIt() {
        // Rising at a jump's takeoff speed: the flight's peak is about a jump's height, still covered.
        double[] rising = MovementReach.heights(false, 0.42, 20);
        double peak = 0;
        for (double h : rising) peak = Math.max(peak, h);
        assertTrue(peak > 1.0 && peak < 1.4, "peak " + peak);
        // Falling: the lowest point of the flight is below the feet, and it is kept.
        double[] falling = MovementReach.heights(false, -0.6, 5);
        double low = 0;
        for (double h : falling) low = Math.min(low, h);
        assertTrue(low < -3.0, "low " + low);
        assertEquals(0.0, falling[0], 0.0, "standing height is always checked");
    }

    @Test
    void aBrokenVerticalVelocityReadsAsTheGroundsHeights() {
        assertArrayEquals(new double[] {0, MovementReach.JUMP_HEIGHT},
            MovementReach.heights(false, Double.NaN, 10), 0.0);
    }

    @Test
    void theGroundOffsetsAreExactlyTheOldOnes() {
        assertEquals(MovementReach.offsets(1.5, -0.5, 4),
            MovementReach.offsets(1.5, -0.5, 4, 0, MovementReach.heights(true, 0, 4)));
    }
}
