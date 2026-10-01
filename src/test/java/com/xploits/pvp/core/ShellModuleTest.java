package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which defence auto-pvp keeps you in (surround++ spec §8). */
class ShellModuleTest {
    @Test
    void theSavedNamesAreFixed() {
        assertEquals("meteor", ShellModule.METEOR.toString());
        assertEquals("xploits++", ShellModule.XPLOITS.toString());
    }

    @Test
    void onlyTheCatalogsSurroundChanges() {
        assertEquals("surround", ShellModule.METEOR.resolve("surround"));
        assertEquals("surround++", ShellModule.XPLOITS.resolve("surround"));
        assertEquals("hole-filler", ShellModule.XPLOITS.resolve("hole-filler"));
        assertEquals("crystal-aura", ShellModule.XPLOITS.resolve("crystal-aura"));
    }
}
