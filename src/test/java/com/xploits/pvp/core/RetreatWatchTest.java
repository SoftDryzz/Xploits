package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetreatWatchTest {
    private static CombatSnapshot at(double distance) {
        return at(distance, "enemy");
    }

    private static CombatSnapshot at(double distance, String id) {
        return Snapshots.of(true, distance, 0, 0, false, false, false, 2, Map.of())
            .withTargetId(id);
    }

    /** Feeds the whole window with the same distance and returns the last verdict. */
    private static boolean fill(RetreatWatch watch, double distance) {
        boolean retreating = false;
        for (int i = 0; i <= RetreatWatch.WINDOW_TICKS; i++) retreating = watch.update(at(distance));
        return retreating;
    }

    @Test
    void aTargetJustSeenIsNeverPullingAway() {
        // Until the window is full there is no ground to compare: nothing is claimed.
        RetreatWatch watch = new RetreatWatch();
        for (int i = 0; i < RetreatWatch.WINDOW_TICKS; i++) {
            assertFalse(watch.update(at(2.0 + i)), "tick " + i + ": the window is not full yet");
        }
    }

    @Test
    void standingStillIsNotPullingAway() {
        assertFalse(fill(new RetreatWatch(), 3.0));
    }

    @Test
    void gainingAWholeBlockOverTheWindowIsPullingAway() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        boolean retreating = false;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) {
            retreating = watch.update(at(2.0 + i * (RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS)));
        }
        assertTrue(retreating);
    }

    @Test
    void gainingLessThanTheThresholdIsNot() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        boolean retreating = false;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) {
            retreating = watch.update(at(2.0 + i * 0.05)); // 0.5 blocks over the whole window
        }
        assertFalse(retreating, "half a block in half a second is moving, not leaving");
    }

    @Test
    void knockbackDoesNotCountAsPullingAway() {
        // A hit pushes you about 0.4 blocks apart at once and it stays there.
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        assertFalse(watch.update(at(2.4)));
        assertFalse(fill(watch, 2.4), "and standing still at the new distance is not either");
    }

    @Test
    void orbitingAroundHimNeverFlipsTheAnswer() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 3.0);

        for (int i = 0; i < 100; i++) {
            assertFalse(watch.update(at(i % 2 == 0 ? 2.5 : 3.5)), "tick " + i);
        }
    }

    @Test
    void theAnswerDoesNotOscillateAtTheThreshold() {
        // The dead band: once inside, it has to drop below STOP_GAIN to leave, so a
        // difference hovering right at START_GAIN cannot turn auto-web on and off on alternate
        // ticks.
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        // It moves away just enough to get in.
        double step = RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) watch.update(at(2.0 + i * step));
        assertTrue(watch.retreating(), "precondition");

        // And now it keeps gaining ground a little slower, hovering around the entry threshold.
        double distance = 2.0 + RetreatWatch.START_GAIN;
        for (int i = 0; i < 50; i++) {
            distance += step * 0.9;
            assertTrue(watch.update(at(distance)), "tick " + i + ": still leaving, it must not flicker");
        }
    }

    @Test
    void stoppingEndsIt() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);
        double step = RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) watch.update(at(2.0 + i * step));
        assertTrue(watch.retreating());

        assertFalse(fill(watch, 2.0 + RetreatWatch.START_GAIN), "it stopped: it is no longer leaving");
    }

    @Test
    void losingTheTargetForgetsTheSeries() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);
        watch.update(CombatSnapshot.none());

        assertFalse(watch.retreating());
        // A huge jump right after must not declare anything: the series starts from zero.
        assertFalse(watch.update(at(9.0)));
    }

    @Test
    void changingTargetForgetsTheSeries() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        // Another player, much further away: comparing their distance with the previous one's would give
        // a huge jump and a web for nobody.
        assertFalse(watch.update(at(9.0, "other")));
        for (int i = 0; i < RetreatWatch.WINDOW_TICKS; i++) {
            assertFalse(watch.update(at(9.0, "other")), "tick " + i);
        }
    }

    @Test
    void resetForgetsEverything() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);
        double step = RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) watch.update(at(2.0 + i * step));
        assertTrue(watch.retreating());

        watch.reset();
        assertFalse(watch.retreating());
        assertFalse(watch.update(at(20.0)));
    }

    @Test
    void theWindowIsHalfASecond() {
        assertEquals(10, RetreatWatch.WINDOW_TICKS, "half a second, the combat time unit of §9");
        assertEquals(1.0, RetreatWatch.START_GAIN, 0.0);
        assertEquals(0.25, RetreatWatch.STOP_GAIN, 0.0);
        assertTrue(RetreatWatch.STOP_GAIN < RetreatWatch.START_GAIN, "without a dead band, it oscillates");
    }
}
