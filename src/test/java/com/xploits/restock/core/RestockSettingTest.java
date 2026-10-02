package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Restock spec §5: the settings, kebab-case, each with its description, with the spec's defaults. */
class RestockSettingTest {
    @Test
    void theIdsAreTheSpecsOwn() {
        assertEquals(List.of("mark-key", "max-distance", "use-stash-keeper", "baritone-settings", "baritone-prefix",
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
}
