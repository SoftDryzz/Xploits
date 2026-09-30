package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** surround++'s settings (spec §8). */
class ShellSettingsTest {
    @Test
    void theDefaultsAreTheSpecs() {
        ShellSettings d = ShellSettings.DEFAULTS;
        assertEquals(2, d.blocksPerTick());
        assertTrue(d.useCryingObsidian());
        assertTrue(d.moveToHole());
        assertTrue(d.denyHoles());
        assertTrue(d.breakCrystals());
        assertFalse(d.burrow(), "burrow is off by default: many anticheats kick for it");
        assertEquals(6.0, d.reach());
    }

    @Test
    void blocksPerTickStaysBetweenOneAndEight() {
        assertThrows(IllegalArgumentException.class, () -> new ShellSettings(0, true, true, true, true, false, 6));
        assertThrows(IllegalArgumentException.class, () -> new ShellSettings(9, true, true, true, true, false, 6));
        assertEquals(1, new ShellSettings(1, true, true, true, true, false, 6).blocksPerTick());
        assertEquals(8, new ShellSettings(8, true, true, true, true, false, 6).blocksPerTick());
    }

    @Test
    void aReachThatIsNotAPositiveNumberIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ShellSettings(2, true, true, true, true, false, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ShellSettings(2, true, true, true, true, false, 0));
    }
}
