package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task A2+ requirement 3: self-surround picks the first missing side, in the caller's own order. */
class SelfSurroundPaceTest {
    @Test
    void theConstantIsTheBriefsExactValue() {
        assertEquals(1, SelfSurroundPace.SURROUND_BLOCKS_PER_TICK);
    }

    @Test
    void everySidePresentNeedsNothing() {
        assertEquals(-1, SelfSurroundPace.nextMissingSide(List.of(true, true, true, true)));
    }

    @Test
    void theFirstMissingSideWins() {
        assertEquals(0, SelfSurroundPace.nextMissingSide(List.of(false, true, false, true)));
        assertEquals(2, SelfSurroundPace.nextMissingSide(List.of(true, true, false, false)));
        assertEquals(3, SelfSurroundPace.nextMissingSide(List.of(true, true, true, false)));
    }

    @Test
    void noSidesAtAllNeedsNothing() {
        assertEquals(-1, SelfSurroundPace.nextMissingSide(List.of()));
    }
}
