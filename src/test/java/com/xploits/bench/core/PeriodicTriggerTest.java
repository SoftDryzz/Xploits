package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+: the shared "every N ticks, first at N, never at T0" cooldown behind several behaviours. */
class PeriodicTriggerTest {
    @Test
    void neverAtOrBeforeT0() {
        assertFalse(PeriodicTrigger.dueAt(Integer.MIN_VALUE, 20));
        assertFalse(PeriodicTrigger.dueAt(-1, 20));
        assertFalse(PeriodicTrigger.dueAt(0, 20));
    }

    @Test
    void firstFiresExactlyAtThePeriod() {
        for (int t = 1; t < 20; t++) assertFalse(PeriodicTrigger.dueAt(t, 20), "tick " + t);
        assertTrue(PeriodicTrigger.dueAt(20, 20));
    }

    @Test
    void repeatsEveryPeriod() {
        for (int cycle = 1; cycle <= 5; cycle++) {
            assertTrue(PeriodicTrigger.dueAt(20 * cycle, 20), "cycle " + cycle);
            assertFalse(PeriodicTrigger.dueAt(20 * cycle - 1, 20), "cycle " + cycle);
            assertFalse(PeriodicTrigger.dueAt(20 * cycle + 1, 20), "cycle " + cycle);
        }
    }

    @Test
    void aNonPositivePeriodIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PeriodicTrigger.dueAt(20, 0));
        assertThrows(IllegalArgumentException.class, () -> PeriodicTrigger.dueAt(20, -1));
    }

    @Test
    void aPeriodOfOneFiresEveryTickAfterT0() {
        assertEquals(true, PeriodicTrigger.dueAt(1, 1));
        assertEquals(true, PeriodicTrigger.dueAt(2, 1));
        assertEquals(false, PeriodicTrigger.dueAt(0, 1));
    }
}
