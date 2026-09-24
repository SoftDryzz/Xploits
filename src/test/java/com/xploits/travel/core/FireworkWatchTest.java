package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireworkWatchTest {
    private static final int THRESHOLD = 16;

    private static FireworkWatch watch() {
        return new FireworkWatch(THRESHOLD);
    }

    @Test
    void aboveTheThresholdThereIsNoWarning() {
        FireworkWatch watch = watch();
        assertFalse(watch.observe(64));
        assertFalse(watch.observe(30));
        assertFalse(watch.observe(THRESHOLD + 1));
    }

    @Test
    void itWarnsExactlyOnReachingTheThreshold() {
        FireworkWatch watch = watch();
        assertFalse(watch.observe(THRESHOLD + 1));
        assertTrue(watch.observe(THRESHOLD), "with exactly the threshold's fireworks it already warns");
    }

    /** The flight is observed twenty times per second: one warning per tick would cover everything else. */
    @Test
    void theWarningDoesNotRepeatEveryTick() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(10));

        for (int fireworks = 10; fireworks >= 0; fireworks--) {
            assertFalse(watch.observe(fireworks), "it does not repeat while still in the band: " + fireworks);
        }
        for (int i = 0; i < 100; i++) {
            assertFalse(watch.observe(0), "not even at zero does it repeat in a loop");
        }
    }

    /**
     * The opposite failure to the loop: staying mute for the rest of the trip. If the player takes
     * fireworks out of a shulker and later runs out again, the warning has to come out again.
     */
    @Test
    void restockingFireworksRearmsTheWarning() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(5));
        assertFalse(watch.observe(3));

        assertFalse(watch.observe(64), "restocking does not warn, it only rearms");

        assertTrue(watch.observe(5), "after the restock the warning can come out again");
    }

    @Test
    void theRestockMustLeaveTheBandToRearm() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(5));

        // Going up inside the band is not a restock that counts: it is still in danger and it already warned.
        assertFalse(watch.observe(THRESHOLD));
        assertFalse(watch.observe(5));

        assertFalse(watch.observe(THRESHOLD + 1), "now it has left the band");
        assertTrue(watch.observe(THRESHOLD));
    }

    @Test
    void itRearmsAsManyTimesAsNeeded() {
        FireworkWatch watch = watch();
        for (int round = 0; round < 5; round++) {
            assertFalse(watch.observe(64), "round " + round);
            assertTrue(watch.observe(0), "round " + round);
            assertFalse(watch.observe(0), "round " + round);
        }
    }

    /** Zero threshold: it only warns when they run out completely, and one is enough to rearm. */
    @Test
    void aZeroThresholdWarnsOnlyWhenRunningOut() {
        FireworkWatch watch = new FireworkWatch(0);
        assertFalse(watch.observe(1));
        assertTrue(watch.observe(0));
        assertFalse(watch.observe(0));
        assertFalse(watch.observe(1));
        assertTrue(watch.observe(0));
    }

    @Test
    void startingWithoutFireworksWarnsOnTheFirstTick() {
        assertTrue(watch().observe(0));
    }

    @Test
    void resetReturnsTheWarningToArmed() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(0));
        assertFalse(watch.observe(0));

        watch.reset();

        assertTrue(watch.observe(0), "a new trip warns again even if the previous one already did");
    }

    @Test
    void theThresholdComesFromTheConstructorAndIsRemembered() {
        assertEquals(THRESHOLD, watch().threshold());

        FireworkWatch early = new FireworkWatch(40);
        assertTrue(early.observe(40), "with a threshold of 40 it warns much earlier");

        assertFalse(new FireworkWatch(2).observe(40), "with a threshold of 2 those same 40 are no worry");
    }

    @Test
    void aNegativeThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FireworkWatch(-1));
    }
}
