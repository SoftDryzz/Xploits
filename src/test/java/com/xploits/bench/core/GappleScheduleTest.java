package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task A1 requirement 4 (gapple model): 32 ticks after a totem pop, the effects are due. A pop while one is
 * already pending re-arms the delay from the new pop; a death cancels whatever is pending. Pure and
 * deterministic.
 */
class GappleScheduleTest {
    @Test
    void thirtyTwoTicksIsTheDelay() {
        assertEquals(32, GappleSchedule.DELAY_TICKS);
    }

    @Test
    void nothingIsDueBeforeAPop() {
        GappleSchedule s = new GappleSchedule();
        assertFalse(s.pending());
        for (int tick = 0; tick < 1000; tick++) assertFalse(s.due(tick));
    }

    @Test
    void dueExactlyThirtyTwoTicksAfterThePop() {
        GappleSchedule s = new GappleSchedule();
        s.pop(100);
        assertTrue(s.pending());
        for (int tick = 100; tick < 132; tick++) assertFalse(s.due(tick), "tick " + tick + " is too early");
        assertTrue(s.due(132));
    }

    @Test
    void firingConsumesTheSchedule() {
        GappleSchedule s = new GappleSchedule();
        s.pop(0);
        assertTrue(s.due(32));
        assertFalse(s.pending());
        assertFalse(s.due(33), "it must not fire twice for the same pop");
    }

    @Test
    void aSecondPopWhilePendingRearmsFromItself() {
        GappleSchedule s = new GappleSchedule();
        s.pop(0);
        s.pop(10);
        assertFalse(s.due(32), "the first pop's delay no longer applies");
        assertTrue(s.due(42));
    }

    @Test
    void deathCancelsWhatIsPending() {
        GappleSchedule s = new GappleSchedule();
        s.pop(0);
        s.death();
        assertFalse(s.pending());
        assertFalse(s.due(32));
    }

    @Test
    void aLateCheckStillFiresOnceAndOnlyOnce() {
        // A tick can be skipped in principle; due() must still catch up and fire exactly once.
        GappleSchedule s = new GappleSchedule();
        s.pop(0);
        assertTrue(s.due(50));
        assertFalse(s.due(51));
    }
}
