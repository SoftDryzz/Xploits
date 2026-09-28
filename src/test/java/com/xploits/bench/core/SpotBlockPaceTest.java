package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+ requirement 2: spot blocking's cadence and reach. */
class SpotBlockPaceTest {
    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(20, SpotBlockPace.BLOCK_EVERY);
        assertEquals(4.5, SpotBlockPace.REACH);
    }

    @Test
    void firstDueAtBlockEveryThenEveryBlockEveryAfter() {
        assertFalse(SpotBlockPace.dueAt(0));
        assertFalse(SpotBlockPace.dueAt(19));
        assertTrue(SpotBlockPace.dueAt(20));
        assertFalse(SpotBlockPace.dueAt(21));
        assertTrue(SpotBlockPace.dueAt(40));
        assertTrue(SpotBlockPace.dueAt(60));
    }
}
