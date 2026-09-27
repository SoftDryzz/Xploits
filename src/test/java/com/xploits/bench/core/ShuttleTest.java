package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The back-and-forth walk of the fight situations (R3-5): a leg out at a steady speed, a pause at the far end,
 * a leg back, a pause at the start, and so on; the pauses follow a fixed sequence so every run is the same.
 */
class ShuttleTest {
    private static final double EPS = 1e-9;

    @Test
    void itWalksOutPausesAndWalksBack() {
        // 4 blocks at 0.5 a tick: 8 ticks a leg, then 2 ticks of pause at each end.
        Shuttle s = new Shuttle(4, 0.5, leg -> 2);
        assertEquals(0, s.offset(0), EPS);
        assertEquals(1, s.direction(0));
        assertEquals(2, s.offset(4), EPS);
        assertEquals(1, s.direction(4));
        // Arrived at tick 8, paused for ticks 8 and 9.
        assertEquals(4, s.offset(8), EPS);
        assertEquals(0, s.direction(8));
        assertEquals(4, s.offset(9), EPS);
        assertEquals(0, s.direction(9));
        // Back from tick 10.
        assertEquals(4, s.offset(10), EPS);
        assertEquals(-1, s.direction(10));
        assertEquals(3.5, s.offset(11), EPS);
        assertEquals(0, s.offset(18), EPS);
        assertEquals(0, s.direction(18));
        // Out again from tick 20.
        assertEquals(0.5, s.offset(21), EPS);
        assertEquals(1, s.direction(21));
    }

    @Test
    void withoutPausesItTurnsAtOnce() {
        Shuttle s = new Shuttle(3, 0.25, leg -> 0);
        assertEquals(3, s.offset(12), EPS);
        assertEquals(-1, s.direction(12));
        assertEquals(2.75, s.offset(13), EPS);
        assertEquals(0, s.offset(24), EPS);
        assertEquals(1, s.direction(24));
    }

    @Test
    void aLegThatDoesNotFitWholeTicksKeepsItsExactLength() {
        // 5 blocks at 0.215 a tick (4.3 b/s): 23.26 ticks a leg; the walk never drifts past either end.
        Shuttle s = new Shuttle(5, 0.215, leg -> 0);
        for (int k = 0; k <= 20 * 60; k++) {
            double o = s.offset(k);
            assertTrue(o >= -EPS && o <= 5 + EPS, "tick " + k + " is off the path");
        }
        // After exactly four legs (93.02 ticks) it is back at the start plus the 0.98 ticks walked since.
        assertEquals(0.215 * (94 - 4 * 5 / 0.215), s.offset(94), 1e-6);
    }

    @Test
    void eachEndTakesTheNextPauseOfTheSequence() {
        // Leg 0 out, pause 1; leg 1 back, pause 3; leg 2 out, pause 1...
        Shuttle s = new Shuttle(1, 1, leg -> leg % 2 == 0 ? 1 : 3);
        List<Double> offsets = new ArrayList<>();
        for (int k = 0; k <= 10; k++) offsets.add(s.offset(k));
        assertEquals(List.of(0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0), offsets);
    }

    @Test
    void beforeT0ItHoldsTheStart() {
        Shuttle s = new Shuttle(4, 0.5, leg -> 2);
        assertEquals(0, s.offset(-1), EPS);
        assertEquals(0, s.direction(-1));
    }

    @Test
    void theIrregularPausesAreFixedAndSpanZeroToTwenty() {
        List<Integer> pauses = Shuttle.IRREGULAR_PAUSES;
        assertTrue(pauses.stream().allMatch(p -> p >= 0 && p <= 20), pauses.toString());
        assertTrue(pauses.contains(0) && pauses.contains(20), "the sequence should reach both ends of 0-20");
        assertTrue(pauses.stream().distinct().count() > pauses.size() / 2, "the sequence should look random");
        for (int leg = 0; leg < 3 * pauses.size(); leg++) {
            assertEquals(pauses.get(leg % pauses.size()), Shuttle.irregularPause(leg));
        }
    }

    @Test
    void twoWalksWithTheSameSequenceAreTheSame() {
        Shuttle a = new Shuttle(5, 0.215, Shuttle::irregularPause);
        Shuttle b = new Shuttle(5, 0.215, Shuttle::irregularPause);
        boolean paused = false;
        for (int k = 0; k <= 600; k++) {
            assertEquals(a.offset(k), b.offset(k), 0);
            assertEquals(a.direction(k), b.direction(k));
            paused |= a.direction(k) == 0;
        }
        assertTrue(paused, "the walk should pause at some end");
    }

    @Test
    void theWalkNeedsALengthASpeedAndNoNegativePause() {
        assertThrows(IllegalArgumentException.class, () -> new Shuttle(0, 1, leg -> 0));
        assertThrows(IllegalArgumentException.class, () -> new Shuttle(1, 0, leg -> 0));
        assertThrows(IllegalArgumentException.class, () -> new Shuttle(1, 1, leg -> -1).offset(5));
        assertFalse(Shuttle.IRREGULAR_PAUSES.isEmpty());
    }
}
