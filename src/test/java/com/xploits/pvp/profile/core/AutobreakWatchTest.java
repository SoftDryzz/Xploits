package com.xploits.pvp.profile.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unticking use-crystal-aura by hand warns; applying a profile never does (precise rules). */
class AutobreakWatchTest {
    @Test
    void trueToFalseByHandWarns() {
        assertTrue(new AutobreakWatch().changed(false, true));
    }

    @Test
    void silentWhileApplyingAProfile() {
        assertFalse(new AutobreakWatch().changed(false, false));
    }

    @Test
    void falseToFalseAndFalseToTrueAreSilent() {
        AutobreakWatch watch = new AutobreakWatch();
        watch.changed(false, false);
        assertFalse(watch.changed(false, true));
        assertFalse(watch.changed(true, true));
    }

    @Test
    void warnsAgainOnEveryNewUntick() {
        AutobreakWatch watch = new AutobreakWatch();
        assertTrue(watch.changed(false, true));
        watch.changed(true, true);
        assertTrue(watch.changed(false, true));
    }

    @Test
    void anUntickWhileApplyingStillMovesThePreviousValue() {
        AutobreakWatch watch = new AutobreakWatch();
        watch.changed(false, false);
        watch.changed(true, false);
        assertTrue(watch.changed(false, true));
    }
}
