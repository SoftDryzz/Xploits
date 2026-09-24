package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallWatchTest {
    private static final int LIMIT_TICKS = 30 * StallWatch.TICKS_PER_SECOND;
    private static final double EPSILON = 1.0;
    private static final int FIRST = 0;

    private static StallWatch watch() {
        return new StallWatch(LIMIT_TICKS, EPSILON);
    }

    /**
     * Runs {@code ticks} ticks at the same distance and returns the tick (counting from 1) at which
     * the watch cut, or 0 if it held out through all of them.
     */
    private static int stillFor(StallWatch watch, int waypoint, double distance, int ticks) {
        for (int i = 1; i <= ticks; i++) {
            if (watch.tick(waypoint, distance)) return i;
        }
        return 0;
    }

    @Test
    void theFirstTickOfAWaypointIsNeverAStall() {
        assertFalse(watch().tick(FIRST, 8_000));
    }

    /** Spec §10's case, pinned: 29 seconds without getting closer does not cut; 30 does. */
    @Test
    void twentyNineSecondsDoNotCutAndThirtyDo() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);

        int twentyNineSeconds = 29 * StallWatch.TICKS_PER_SECOND;
        assertEquals(0, stillFor(watch, FIRST, 8_000, twentyNineSeconds),
            "at 29 seconds it still does not cut");

        assertEquals(LIMIT_TICKS - twentyNineSeconds, stillFor(watch, FIRST, 8_000, StallWatch.TICKS_PER_SECOND),
            "the cut lands exactly on tick 600, the 30-second one");
    }

    @Test
    void gettingCloserResetsTheCount() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);
        stillFor(watch, FIRST, 8_000, LIMIT_TICKS - 1);

        assertFalse(watch.tick(FIRST, 7_000), "it got a thousand blocks closer: there is no stall");
        assertEquals(0, watch.ticksWithoutProgress());
        assertEquals(0, stillFor(watch, FIRST, 7_000, LIMIT_TICKS - 1), "the count starts again from zero");
    }

    /**
     * Getting closer by less than the epsilon is not progress: if it counted, the one-block sway of
     * the flight itself would rearm the counter forever and the watch would never cut.
     */
    @Test
    void gettingCloserBelowTheEpsilonIsNotProgress() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);

        // Half a block over the whole thirty seconds: less than the epsilon, so it is not progress
        // however much the distance drops on every single tick.
        double step = 0.5 / LIMIT_TICKS;
        double distance = 8_000;
        int cut = 0;
        for (int i = 1; i <= LIMIT_TICKS; i++) {
            distance -= step;
            if (watch.tick(FIRST, distance)) {
                cut = i;
                break;
            }
        }

        assertEquals(LIMIT_TICKS, cut, "getting half a block closer in 30 seconds must not prevent the cut");
    }

    /** And the epsilon is a parameter, not a hidden constant: with a bigger one, the half block does not count either. */
    @Test
    void theEpsilonComesFromTheConstructor() {
        StallWatch tight = new StallWatch(LIMIT_TICKS, 0.1);
        tight.tick(FIRST, 8_000);
        assertFalse(tight.tick(FIRST, 7_999.5), "with epsilon 0.1 half a block is progress");

        StallWatch loose = new StallWatch(LIMIT_TICKS, 50);
        loose.tick(FIRST, 8_000);
        loose.tick(FIRST, 7_999.5);
        assertEquals(1, loose.ticksWithoutProgress(), "with epsilon 50 half a block is not progress");
    }

    @Test
    void theLimitComesFromTheConstructor() {
        StallWatch quick = new StallWatch(3, EPSILON);
        quick.tick(FIRST, 100);
        assertEquals(3, stillFor(quick, FIRST, 100, 10));
        assertEquals(3, new StallWatch(60, EPSILON).limitSeconds());
    }

    /**
     * The case that would cut a perfect trip: on moving to the next waypoint the distance jumps up all
     * at once. Neither that jump nor the previous waypoint's still ticks may be dragged into the new one.
     */
    @Test
    void changingWaypointResetsTheWatch() {
        StallWatch watch = watch();
        watch.tick(0, 8_000);
        stillFor(watch, 0, 8_000, LIMIT_TICKS - 1);
        assertEquals(LIMIT_TICKS - 1, watch.ticksWithoutProgress(), "one tick away from cutting");

        // Waypoint 0 is reached and it aims at 1, which is 12 000 blocks away: the distance goes up.
        assertFalse(watch.tick(1, 12_000), "the waypoint jump is not a stall");
        assertEquals(0, watch.ticksWithoutProgress(), "the previous waypoint's count is not dragged along");

        // And the old waypoint's distance is not the new one's reference: 11 000 is still progress even
        // though it is much worse than the 8 000 from before.
        assertFalse(watch.tick(1, 11_000));
        assertEquals(0, stillFor(watch, 1, 11_000, LIMIT_TICKS - 1),
            "the new waypoint has its own whole 30 seconds");
    }

    @Test
    void theNewWaypointCanStillStall() {
        StallWatch watch = watch();
        watch.tick(0, 8_000);
        watch.tick(1, 12_000);
        assertEquals(LIMIT_TICKS, stillFor(watch, 1, 12_000, LIMIT_TICKS));
    }

    @Test
    void resetReturnsTheWatchToTheStart() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);
        stillFor(watch, FIRST, 8_000, LIMIT_TICKS - 1);

        watch.reset();

        assertEquals(0, watch.ticksWithoutProgress());
        assertFalse(watch.tick(FIRST, 8_000), "after the reset, the first tick sets the reference again");
        assertEquals(0, stillFor(watch, FIRST, 8_000, LIMIT_TICKS - 1));
    }

    @Test
    void aRisingDistanceWithoutAWaypointChangeIsAStall() {
        // Moving away is not getting closer: if Baritone goes the opposite way, that is cut all the same.
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);
        int cut = 0;
        for (int i = 1; i <= LIMIT_TICKS; i++) {
            if (watch.tick(FIRST, 8_000 + i)) {
                cut = i;
                break;
            }
        }
        assertEquals(LIMIT_TICKS, cut);
    }

    @Test
    void degenerateParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(0, EPSILON));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(-1, EPSILON));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(LIMIT_TICKS, -0.5));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(LIMIT_TICKS, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(LIMIT_TICKS, Double.POSITIVE_INFINITY));
    }

    @Test
    void ofSecondsConvertsAtTwentyTicksPerSecond() {
        StallWatch watch = StallWatch.ofSeconds(30, EPSILON);
        watch.tick(FIRST, 100);
        assertEquals(LIMIT_TICKS, stillFor(watch, FIRST, 100, LIMIT_TICKS));
        assertEquals(30, watch.limitSeconds());
    }

    @Test
    void aZeroEpsilonStillCutsIfTheDistanceDoesNotMove() {
        StallWatch watch = new StallWatch(LIMIT_TICKS, 0);
        watch.tick(FIRST, 8_000);
        assertEquals(LIMIT_TICKS, stillFor(watch, FIRST, 8_000, LIMIT_TICKS));
        assertTrue(watch.ticksWithoutProgress() >= LIMIT_TICKS);
    }
}
