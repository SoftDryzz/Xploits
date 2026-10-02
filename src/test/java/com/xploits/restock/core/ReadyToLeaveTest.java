package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred m2 (after ruling R32): a due trip leaves only once {@link RestockTrip#mayLeave} has held for
 * {@code leaveTicks} ticks in a row — a key let go for one tick while building at an edge is not the player standing.
 */
class ReadyToLeaveTest {
    @Test
    void tenTicksInARow() {
        assertEquals(10, RestockLimits.DEFAULTS.leaveTicks());
        ReadyToLeave r = new ReadyToLeave(RestockLimits.DEFAULTS.leaveTicks());
        for (int i = 1; i <= 9; i++) assertFalse(r.tick(true), "tick " + i);
        assertTrue(r.tick(true), "the tenth");
        assertTrue(r.tick(true), "and while it keeps holding");
    }

    @Test
    void aKeyLetGoForOneTickIsNotEnough() {
        ReadyToLeave r = new ReadyToLeave(10);
        for (int i = 1; i <= 30; i++) {
            assertFalse(r.tick(i % 2 == 0), "tapping keys, tick " + i);
        }
    }

    @Test
    void oneTickNotReadyStartsTheCountAgain() {
        ReadyToLeave r = new ReadyToLeave(10);
        for (int i = 1; i <= 9; i++) r.tick(true);
        assertFalse(r.tick(false), "a key pressed");
        for (int i = 1; i <= 9; i++) assertFalse(r.tick(true), "again, tick " + i);
        assertTrue(r.tick(true));
        assertFalse(r.tick(false), "and it stops being ready at once");
    }

    @Test
    void fewerThanOneTickIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReadyToLeave(0));
    }
}
