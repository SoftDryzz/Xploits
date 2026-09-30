package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** surround++'s setting names, as a player types them and Meteor saves them (spec §8). */
class ShellSettingTest {
    @Test
    void theNamesAreTheSpecs() {
        assertEquals(List.of("blocks-per-tick", "use-crying-obsidian", "move-to-hole", "deny-holes", "break-crystals", "burrow",
            "reach"), Arrays.stream(ShellSetting.values()).map(ShellSetting::id).toList());
    }

    @Test
    void everySettingHasItsDescription() {
        for (ShellSetting s : ShellSetting.values()) assertEquals("SETTING_" + s.name(), s.text().name());
    }
}
