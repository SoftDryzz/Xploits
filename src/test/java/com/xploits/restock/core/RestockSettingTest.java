package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §5: the settings, kebab-case, each with its description, with the spec's defaults. */
class RestockSettingTest {
    @Test
    void theIdsAreTheSpecsOwn() {
        assertEquals(List.of("mark-key", "max-distance", "use-stash-keeper", "use-carried-shulkers",
            "baritone-settings", "baritone-prefix",
            "stop-near-players", "player-distance", "min-health"),
            Arrays.stream(RestockSetting.values()).map(RestockSetting::id).toList());
        for (RestockSetting s : RestockSetting.values()) assertEquals("SETTING_" + s.name(), s.text().name());
    }

    @Test
    void theDefaultsAndTheirBounds() {
        assertEquals(new RestockSettings(64, true, BaritoneSession.Mode.MINE, "#", true, 48, 10), RestockSettings.DEFAULTS);
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(7, true, BaritoneSession.Mode.MINE, "#", true, 48, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(257, true, BaritoneSession.Mode.MINE, "#", true, 48, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(64, true, BaritoneSession.Mode.MINE, "#", true, 7, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(64, true, BaritoneSession.Mode.MINE, "#", true, 48, 0.5));
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(64, true, BaritoneSession.Mode.MINE, null, true, 48, 10));
    }

    private static RestockSettings of(int maxDistance, int playerDistance, double minHealth) {
        return new RestockSettings(maxDistance, true, BaritoneSession.Mode.MINE, "#", true, playerDistance, minHealth);
    }

    @Test
    void theInclusiveEdgesOfMaxDistanceAreAccepted() {
        of(8, 48, 10);
        of(256, 48, 10);
        assertThrows(IllegalArgumentException.class, () -> of(7, 48, 10));
        assertThrows(IllegalArgumentException.class, () -> of(257, 48, 10));
    }

    @Test
    void theInclusiveEdgesOfPlayerDistanceAreAccepted() {
        of(64, 8, 10);
        of(64, 256, 10);
        assertThrows(IllegalArgumentException.class, () -> of(64, 7, 10));
        assertThrows(IllegalArgumentException.class, () -> of(64, 257, 10));
    }

    @Test
    void theInclusiveEdgesOfMinHealthAreAccepted() {
        of(64, 48, 1);
        of(64, 48, 36);
        assertThrows(IllegalArgumentException.class, () -> of(64, 48, 0.99));
        assertThrows(IllegalArgumentException.class, () -> of(64, 48, 36.5));
        assertThrows(IllegalArgumentException.class, () -> of(64, 48, Double.NaN));
    }

    @Test
    void aMissingBaritoneSettingIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(64, true, null, "#", true, 48, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new RestockSettings(64, true, BaritoneSession.Mode.MINE, null, true, 48, 10));
    }

    @Test
    void theCarriedShulkersAreUsedByDefault() {
        // Owner ruling R44: on by default; the settings read before it existed keep it on.
        assertTrue(RestockSettings.DEFAULTS.useCarriedShulkers());
        assertTrue(new RestockSettings(64, true, BaritoneSession.Mode.MINE, "#", true, 48, 10).useCarriedShulkers());
        assertEquals(false,
            new RestockSettings(64, true, BaritoneSession.Mode.MINE, "#", true, 48, 10, false).useCarriedShulkers());
    }
}
