package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+ requirement 1: autobreak's range and its "not the opponent's own" exclusion. */
class AutobreakRangeTest {
    @Test
    void theConstantIsTheBriefsExactValue() {
        assertEquals(4.5, AutobreakRange.AUTOBREAK_RANGE);
    }

    @Test
    void withinRangeAndForeignIsBroken() {
        assertTrue(AutobreakRange.shouldBreak(4.5, false));
        assertTrue(AutobreakRange.shouldBreak(0, false));
        assertTrue(AutobreakRange.shouldBreak(4.4999, false));
    }

    @Test
    void beyondRangeIsNeverBroken() {
        assertFalse(AutobreakRange.shouldBreak(4.5001, false));
        assertFalse(AutobreakRange.shouldBreak(100, false));
    }

    @Test
    void theOpponentsOwnCrystalIsNeverBroken() {
        assertFalse(AutobreakRange.shouldBreak(0, true));
        assertFalse(AutobreakRange.shouldBreak(4.5, true));
    }
}
