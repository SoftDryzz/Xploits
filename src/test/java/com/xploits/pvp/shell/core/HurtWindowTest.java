package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Our own hurt window (surround++ spec §6.4): a smaller hit inside it is ignored by the server. */
class HurtWindowTest {
    @Test
    void noWindowSwallowsNothing() {
        assertFalse(HurtWindow.NONE.swallows(0.5, 0));
    }

    @Test
    void aSmallerHitInsideTheWindowIsSwallowed() {
        // 2 ticks since a 12-damage hit and 3 ticks of round trip: 5 < 10, and 10 <= 12 - 0.5.
        assertTrue(new HurtWindow(2, 12).swallows(10, 3));
    }

    @Test
    void theRoundTripCountsAgainstTheWindow() {
        assertTrue(new HurtWindow(2, 12).swallows(10, 7), "2 + 7 = 9 ticks: still inside");
        assertFalse(new HurtWindow(2, 12).swallows(10, 8), "2 + 8 = 10 ticks: the window has closed");
    }

    @Test
    void aHitCloseToTheLastOneIsNotTrusted() {
        assertTrue(new HurtWindow(0, 12).swallows(11.5, 0), "exactly the margin below");
        assertFalse(new HurtWindow(0, 12).swallows(11.6, 0), "less than half a point below: our prediction is not the server's");
    }

    @Test
    void oddValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new HurtWindow(-2, 1));
        assertThrows(IllegalArgumentException.class, () -> new HurtWindow(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new HurtWindow(0, -1));
    }
}
