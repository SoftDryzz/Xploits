package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SequenceTrackerTest {
    @Test
    void consecutiveEntriesLoseNothing() {
        SequenceTracker s = new SequenceTracker();
        assertEquals(0, s.gap("a", 1));
        assertEquals(0, s.gap("a", 2));
    }

    @Test
    void aJumpOfFiveIsFourLost() {
        SequenceTracker s = new SequenceTracker();
        s.gap("a", 1);
        assertEquals(4, s.gap("a", 6));
    }

    @Test
    void goingBackwardsIsAnError() {
        SequenceTracker s = new SequenceTracker();
        s.gap("a", 5);
        assertThrows(IllegalStateException.class, () -> s.gap("a", 5));
        assertThrows(IllegalStateException.class, () -> s.gap("a", 3));
    }

    @Test
    void aNewSessionStartsFromZero() {
        SequenceTracker s = new SequenceTracker();
        s.gap("a", 10);
        assertEquals(0, s.gap("b", 0));
    }
}
