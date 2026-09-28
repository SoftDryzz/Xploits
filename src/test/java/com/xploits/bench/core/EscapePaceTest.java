package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+ requirement 6: the escape's trigger and its steady ramp to the full distance. */
class EscapePaceTest {
    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(2, EscapePace.ESCAPE_TOTEMS);
        assertEquals(12, EscapePace.ESCAPE_DISTANCE);
        assertEquals(10, EscapePace.ESCAPE_TICKS);
    }

    @Test
    void triggersAtOrBelowEscapeTotems() {
        assertTrue(EscapePace.triggered(0));
        assertTrue(EscapePace.triggered(1));
        assertTrue(EscapePace.triggered(2));
        assertFalse(EscapePace.triggered(3));
        assertFalse(EscapePace.triggered(8));
    }

    @Test
    void noDisplacementAtOrBeforeTheTrigger() {
        assertEquals(0, EscapePace.displacement(0));
        assertEquals(0, EscapePace.displacement(-1));
    }

    @Test
    void reachesTheFullDistanceExactlyAtEscapeTicks() {
        assertEquals(12.0, EscapePace.displacement(EscapePace.ESCAPE_TICKS), 1e-9);
    }

    @Test
    void rampsHalfwayAtHalfTheTicks() {
        assertEquals(6.0, EscapePace.displacement(5), 1e-9);
    }

    @Test
    void neverOvershootsPastTheFullDistance() {
        assertEquals(12.0, EscapePace.displacement(11), 1e-9);
        assertEquals(12.0, EscapePace.displacement(1000), 1e-9);
    }
}
