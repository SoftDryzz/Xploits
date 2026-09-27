package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breached-hole memory on its own (task R3-13 fix 2, extracted in review round 1). Pure: no
 * game to start, so every path -arming, the 40-tick boundary, each forgetting trigger and re-arming-
 * is pinned here instead of only through {@link DefensivePolicyTest}'s pre-computed fact.
 */
class HoleMemoryTest {
    private static final long FEET = 12345L;
    private static final long OTHER_FEET = 67890L;

    @Test
    void neverInAHoleIsNeverBreached() {
        HoleMemory memory = new HoleMemory();
        assertFalse(memory.tick(1, false, true, false, FEET));
    }

    @Test
    void beingInAFullHoleIsRememberedForLaterButIsNotBreachedYet() {
        // "in hole -> remembered": there is nothing to patch while the hole is still whole.
        HoleMemory memory = new HoleMemory();
        assertFalse(memory.tick(5, true, true, false, FEET), "still in the hole: nothing breached");
    }

    @Test
    void leavingTheHoleTheVeryNextTickIsBreached() {
        HoleMemory memory = new HoleMemory();
        memory.tick(5, true, true, false, FEET);
        assertTrue(memory.tick(6, false, true, false, FEET),
            "isInHole already reads false the tick the breach happens: this is exactly the gap the "
                + "owner's real log found (research §0.2)");
    }

    @Test
    void breachedHoldsThroughTickFortyAndDropsAtFortyOne() {
        // Pins the exact boundary (BREACH_MEMORY_TICKS = 40), stepping one real tick at a time the
        // way the adapter actually calls this every tick.
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);

        for (long t = 1; t <= CombatDirector.BREACH_MEMORY_TICKS; t++) {
            assertTrue(memory.tick(t, false, true, false, FEET), "tick " + t + ": still within the window");
        }
        assertFalse(memory.tick(CombatDirector.BREACH_MEMORY_TICKS + 1, false, true, false, FEET),
            "one tick past the window: no longer breached");
    }

    @Test
    void movingToADifferentBlockForgetsItOutright() {
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);

        assertFalse(memory.tick(1, false, true, false, OTHER_FEET), "a different block: not breached");
        // Forgotten outright, not merely masked for that one tick: coming back later must not revive it.
        assertFalse(memory.tick(2, false, true, false, FEET), "the memory of the old block is gone too");
    }

    @Test
    void aHeightChangeForgetsItOutright() {
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);

        assertFalse(memory.tick(1, false, true, true, FEET), "your height just moved: not breached");
        assertFalse(memory.tick(2, false, true, false, FEET), "and it stays forgotten with your height still again");
    }

    @Test
    void leavingTheGroundForgetsItOutright() {
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);

        assertFalse(memory.tick(1, false, false, false, FEET), "off the ground: not breached");
        assertFalse(memory.tick(2, false, true, false, FEET), "and it stays forgotten once you land again");
    }

    @Test
    void walkingOutAndBackInOnlyAFreshFullHoleSightingReArmsIt() {
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);
        // Walks off (forgets), then stands back on the very same block without ever being in a full
        // hole there again: that alone must not count as a new breach.
        memory.tick(1, false, true, false, OTHER_FEET);
        assertFalse(memory.tick(2, false, true, false, FEET),
            "standing on the old block again is not the same as a fresh full-hole sighting");

        // A genuine new full-hole tick on that block re-arms it, and the tick after is breached again.
        memory.tick(3, true, true, false, FEET);
        assertTrue(memory.tick(4, false, true, false, FEET), "a fresh sighting re-arms it");
    }

    @Test
    void deathWorldChangeOrDeactivateForgetsIt() {
        // The adapter calls forget() on all three (death, world change, deactivate): one behaviour,
        // exercised once here since the pure class cannot tell which of the three it was.
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);

        memory.forget();

        assertFalse(memory.tick(1, false, true, false, FEET), "forgotten: the same block does not revive it");
    }

    @Test
    void reArmingAfterALaterInHoleTickUsesTheNewTickAsTheWindowStart() {
        // The 40-tick window always counts from the LAST full-hole sighting, not the first.
        HoleMemory memory = new HoleMemory();
        memory.tick(0, true, true, false, FEET);
        memory.tick(10, true, true, false, FEET);

        assertTrue(memory.tick(10 + CombatDirector.BREACH_MEMORY_TICKS, false, true, false, FEET),
            "counted from tick 10, not tick 0");
        assertFalse(memory.tick(10 + CombatDirector.BREACH_MEMORY_TICKS + 1, false, true, false, FEET));
    }
}
