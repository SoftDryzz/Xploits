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

    /** Fix round 1 (review-a3.md finding 2): the candidate spot's support block is one level below the
     * opponent's own feet block, the same level the arena's floor sits at — not the opponent's own feet level,
     * which is open air in every fight (a height bug: the check could never find an obsidian/bedrock base). */
    @Test
    void theSupportBlockIsOneLevelBelowTheOpponentsFeet() {
        assertEquals(1, SpotBlockPace.SUPPORT_BELOW);
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
