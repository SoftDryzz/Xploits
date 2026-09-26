package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static com.xploits.pvp.crystal.core.CrystalSetting.AUTO_SWITCH;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_ON_MINE;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_ON_USE;
import static com.xploits.pvp.crystal.core.CrystalSetting.SWING_MODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enum settings survive a save and a load. Meteor's {@code EnumSetting} (1.21.11 sources) saves
 * {@code value.toString()} (line 48) and loads it back with {@code parseImpl} (lines 28-30): the first constant
 * whose {@code toString} equals the saved text, ignoring case. The texts are also what a player types.
 */
class CrystalSettingsPersistenceTest {
    /** {@code EnumSetting.parseImpl}, as Meteor loads a saved value. */
    private static <T extends Enum<T>> T parse(T[] values, String saved) {
        for (T value : values) {
            if (saved.equalsIgnoreCase(value.toString())) return value;
        }
        return null;
    }

    private static <T extends Enum<T>> void roundTrips(T[] values) {
        Set<String> seen = new HashSet<>();
        for (T value : values) {
            String saved = value.toString();
            assertSame(value, parse(values, saved), saved);
            assertSame(value, parse(values, saved.toUpperCase(Locale.ROOT)), saved);
            assertTrue(seen.add(saved.toLowerCase(Locale.ROOT)), "two values save as " + saved);
        }
    }

    @Test
    @Covers({AUTO_SWITCH, PAUSE_ON_USE, PAUSE_ON_MINE, SWING_MODE})
    void everyEnumSettingLoadsWhatItSaved() {
        roundTrips(AutoSwitch.values());
        roundTrips(PauseMode.values());
        roundTrips(SwingMode.values());
    }

    @Test
    @Covers({AUTO_SWITCH, PAUSE_ON_USE, PAUSE_ON_MINE, SWING_MODE})
    void theSavedTextsAreMeteorsOwn() {
        // Meteor's constants (CrystalAura lines 1359-1363, 1371-1375, 1382-1386) save as their names, so a
        // value typed or saved for Meteor's aura means the same here. Silent is left out on purpose (Q6).
        assertEquals(List.of("None", "Normal"), texts(AutoSwitch.values()));
        assertEquals(List.of("Both", "Place", "Break", "None"), texts(PauseMode.values()));
        assertEquals(List.of("Both", "Packet", "Client", "None"), texts(SwingMode.values()));
        assertEquals(null, parse(AutoSwitch.values(), "Silent"));
    }

    private static List<String> texts(Enum<?>[] values) {
        return Stream.of(values).map(Object::toString).toList();
    }
}
