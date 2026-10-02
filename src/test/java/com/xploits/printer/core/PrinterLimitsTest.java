package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The constants of the cores (printer spec §9, N-I5): a change is a decision, so every value is pinned. */
class PrinterLimitsTest {
    private static final PrinterLimits D = PrinterLimits.DEFAULTS;

    @Test
    void theDefaultsAreTheSettledValues() {
        assertEquals(100, D.breakCapTicks());
        assertEquals(10, D.durabilityFloor());
        assertEquals(0.1, D.hitMargin());
        assertEquals(3, D.failedPlacements());
        assertEquals(20, D.yieldTicks());
        assertEquals(40, D.clearTicks());
        assertEquals(3, D.pausesBeforeStop());
        assertEquals(3, D.unreachableSpots());
        assertEquals(4, D.pendingMinTicks());
        assertEquals(-101, D.rotationPriority());
        assertEquals(4, D.unknownGoals());
        assertEquals(4.5, D.maxReach());
        assertEquals(6, D.breakGapTicks());
        assertEquals(1.5, D.lagSeconds());
        assertEquals(16384, D.scanBudget());
        assertEquals(4_194_304L, D.maxVolume());
        assertEquals(64, D.rayBudget());
        assertEquals(200, D.walkStallTicks());
        assertEquals(0.5, D.walkProgress());
    }

    @Test
    void theRotationPriorityIsBelowEveryCombatRequest() {
        // Spike S6: the lowest real request in use is the -100 hold of crystal-aura and crystal-aura++.
        assertTrue(D.rotationPriority() < -100);
    }

    @Test
    void impossibleLimitsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PrinterLimits(0, 10, 0.1, 3, 20, 40, 3, 3, 4, -101, 4,
            4.5, 6, 1.5, 16384, 4_194_304L, 64, 200, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new PrinterLimits(100, 10, 0.5, 3, 20, 40, 3, 3, 4, -101, 4,
            4.5, 6, 1.5, 16384, 4_194_304L, 64, 200, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new PrinterLimits(100, 10, 0.1, 3, 20, 40, 3, 3, 4, -100, 4,
            4.5, 6, 1.5, 16384, 4_194_304L, 64, 200, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new PrinterLimits(100, 10, 0.1, 3, 20, 40, 3, 3, 4, -101, 4,
            4.6, 6, 1.5, 16384, 4_194_304L, 64, 200, 0.5));
    }
}
