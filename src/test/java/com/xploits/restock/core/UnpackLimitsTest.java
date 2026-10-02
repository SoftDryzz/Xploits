package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The unpacking's constants, pinned: a change here is a decision, not a refactor. */
class UnpackLimitsTest {
    @Test
    void theDefaults() {
        assertEquals(new UnpackLimits(40, 3, 4, 40, 3, 40, 20, 100, 100, 200), UnpackLimits.DEFAULTS);
    }

    @Test
    void impossibleValuesAreRefused() {
        refused(0, 3, 4, 40, 3, 40, 20, 100, 100, 200);
        refused(40, 0, 4, 40, 3, 40, 20, 100, 100, 200);
        refused(40, 3, -1, 40, 3, 40, 20, 100, 100, 200);
        refused(40, 3, 4, 4, 3, 40, 20, 100, 100, 200);
        refused(40, 3, 4, 40, -1, 40, 20, 100, 100, 200);
        refused(40, 3, 4, 40, 3, 0, 20, 100, 100, 200);
        refused(40, 3, 4, 40, 3, 40, 0, 100, 100, 200);
        refused(40, 3, 4, 40, 3, 40, 20, 20, 100, 200);
        refused(40, 3, 4, 40, 3, 40, 20, 100, 0, 200);
        refused(40, 3, 4, 40, 3, 40, 20, 100, 100, 0);
    }

    @Test
    void theSmallestValuesAllowed() {
        // No settle at all, the settle limit one tick past it, no restart, the walk onto the drop one tick before its limit.
        new UnpackLimits(1, 1, 0, 1, 0, 1, 1, 2, 1, 1);
    }

    private static void refused(int... v) {
        assertThrows(IllegalArgumentException.class,
            () -> new UnpackLimits(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]));
    }
}
