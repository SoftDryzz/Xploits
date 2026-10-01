package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The walk into a hole (surround++ spec §7.1): a few ticks of movement keys, and your own keys always win. */
class HoleWalkTest {
    private static final Optional<Vec> FAR_ENOUGH = Optional.of(new Vec(2, 0, 0));

    @Test
    void keysPointTheWayWhateverYouFace() {
        // Minecraft: yaw 0 faces +z (south), and the left key moves you +x when you face south.
        assertEquals(Set.of(HoleWalk.Key.FORWARD), HoleWalk.keysFor(0, 1, 0));
        assertEquals(Set.of(HoleWalk.Key.BACK), HoleWalk.keysFor(0, -1, 0));
        assertEquals(Set.of(HoleWalk.Key.LEFT), HoleWalk.keysFor(1, 0, 0));
        assertEquals(Set.of(HoleWalk.Key.RIGHT), HoleWalk.keysFor(-1, 0, 0));
        assertEquals(Set.of(HoleWalk.Key.FORWARD, HoleWalk.Key.LEFT), HoleWalk.keysFor(1, 1, 0));
        // Yaw 90 faces -x (west); yaw 180 faces -z (north).
        assertEquals(Set.of(HoleWalk.Key.FORWARD), HoleWalk.keysFor(-1, 0, 90));
        assertEquals(Set.of(HoleWalk.Key.FORWARD), HoleWalk.keysFor(0, -1, 180));
    }

    @Test
    void aWalkEndsWhenYouArriveAndYouMayWalkAgainAtOnce() {
        HoleWalk walk = new HoleWalk();
        assertEquals(Set.of(HoleWalk.Key.LEFT), walk.start(new Vec(2, 0, 0), 0));
        assertTrue(walk.walking());
        HoleWalk.Step step = walk.step(Optional.of(new Vec(0.1, 0, 0.05)), false, false, 0);
        assertTrue(step.stopped());
        assertTrue(step.keys().isEmpty());
        assertTrue(walk.ready());
    }

    @Test
    void theWalkStopsAtOnceWhenYouPressAKey() {
        HoleWalk walk = new HoleWalk();
        walk.start(new Vec(2, 0, 0), 0);
        assertTrue(walk.step(FAR_ENOUGH, true, false, 0).stopped());
        assertFalse(walk.ready(), "it waits before choosing a hole again");
        for (int i = 0; i < HoleWalk.COOLDOWN_TICKS - 1; i++) walk.idle();
        assertFalse(walk.ready());
        walk.idle();
        assertTrue(walk.ready());
    }

    @Test
    void theWalkStopsInAWeb() {
        HoleWalk walk = new HoleWalk();
        walk.start(new Vec(2, 0, 0), 0);
        assertTrue(walk.step(FAR_ENOUGH, false, true, 0).stopped());
    }

    @Test
    void theWalkGivesUpAfterTwoSeconds() {
        HoleWalk walk = new HoleWalk();
        walk.start(new Vec(2, 0, 0), 0);
        for (int i = 0; i < HoleWalk.TIMEOUT_TICKS; i++) assertFalse(walk.step(FAR_ENOUGH, false, false, 0).stopped());
        assertTrue(walk.step(FAR_ENOUGH, false, false, 0).stopped());
    }

    @Test
    void theWalkStopsWhenTheHoleIsSuddenlyFarAway() {
        HoleWalk walk = new HoleWalk();
        walk.start(new Vec(2, 0, 0), 0);
        assertTrue(walk.step(Optional.of(new Vec(6, 0, 0)), false, false, 0).stopped(), "a teleport, a knockback across the arena");
    }

    @Test
    void aLostTargetStopsTheWalk() {
        HoleWalk walk = new HoleWalk();
        walk.start(new Vec(2, 0, 0), 0);
        assertTrue(walk.step(Optional.empty(), false, false, 0).stopped());
    }

    @Test
    void steppingWithoutAWalkIsAMistake() {
        assertThrows(IllegalStateException.class, () -> new HoleWalk().step(FAR_ENOUGH, false, false, 0));
    }
}
