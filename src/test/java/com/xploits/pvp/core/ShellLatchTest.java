package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shell-module latch (surround++ spec §8): the same order as the crystal aura's. */
class ShellLatchTest {
    private boolean running = true;
    private final List<String> log = new ArrayList<>();
    private ShellLatch latch;

    private ShellLatch latch() {
        latch = new ShellLatch(() -> running, () -> log.add("release with " + latch.latched()), now -> log.add("moved to " + now));
        return latch;
    }

    @Test
    void aChangeReleasesEverythingWithTheOldShellLatchedThenMoves() {
        ShellLatch l = latch();
        l.latch(ShellModule.METEOR);
        l.requested(ShellModule.XPLOITS);
        assertTrue(l.settle());
        assertEquals(List.of("release with meteor", "moved to xploits++"), log);
        assertEquals(ShellModule.XPLOITS, l.latched());
        assertEquals("surround++", l.resolve("surround"));
    }

    @Test
    void aLoadThatEndsOnTheLatchedValueIsNoChange() {
        ShellLatch l = latch();
        l.latch(ShellModule.XPLOITS);
        l.requested(ShellModule.METEOR);
        l.requested(ShellModule.XPLOITS);
        assertFalse(l.settle());
        assertTrue(log.isEmpty());
    }

    @Test
    void withAutoPvpOffNothingMoves() {
        ShellLatch l = latch();
        l.latch(ShellModule.METEOR);
        running = false;
        l.requested(ShellModule.XPLOITS);
        assertFalse(l.settle());
        assertEquals(ShellModule.METEOR, l.latched());
    }

    @Test
    void activationDropsWhateverWasPending() {
        ShellLatch l = latch();
        l.requested(ShellModule.XPLOITS);
        l.latch(ShellModule.METEOR);
        assertFalse(l.settle());
    }
}
