package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+ requirement 4: hole fill's cadence, radius, and nearest-first choice. */
class HoleFillPaceTest {
    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(40, HoleFillPace.HOLE_FILL_EVERY);
        assertEquals(3, HoleFillPace.HOLE_FILL_RADIUS);
    }

    @Test
    void firstDueAtHoleFillEveryThenEveryHoleFillEveryAfter() {
        assertFalse(HoleFillPace.dueAt(39));
        assertTrue(HoleFillPace.dueAt(40));
        assertFalse(HoleFillPace.dueAt(41));
        assertTrue(HoleFillPace.dueAt(80));
    }

    @Test
    void oneCandidateIsAlwaysChosen() {
        assertEquals(0, HoleFillPace.nearestHole(List.of(2.5)));
    }

    @Test
    void theNearestWins() {
        assertEquals(2, HoleFillPace.nearestHole(List.of(3.0, 2.9, 1.0, 2.0)));
    }

    @Test
    void aTieKeepsTheFirstInTheList() {
        assertEquals(0, HoleFillPace.nearestHole(List.of(1.0, 1.0, 2.0)));
        assertEquals(1, HoleFillPace.nearestHole(List.of(3.0, 1.0, 1.0)));
    }

    @Test
    void anEmptyListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> HoleFillPace.nearestHole(List.of()));
    }
}
