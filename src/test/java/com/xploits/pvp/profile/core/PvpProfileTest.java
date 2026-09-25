package com.xploits.pvp.profile.core;

import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Name and range validation, and the tolerant {@code modified} comparison (design §2). */
class PvpProfileTest {
    private static final Set<String> ALL = allModuleNames();

    private static Set<String> allModuleNames() {
        return ManagedModules.ALL.stream().map(ManagedModule::name).collect(Collectors.toUnmodifiableSet());
    }

    private static PvpProfile profile(String name) {
        return new PvpProfile(name, 16, 6, 12.0, ALL);
    }

    // --- name ---

    @Test
    void aOneCharacterNameIsValid() {
        assertEquals("a", profile("a").name());
    }

    @Test
    void aSixteenCharacterNameIsValid() {
        String name = "abcdefghij0123-4".substring(0, 16);
        assertEquals(16, name.length());
        assertEquals(name, profile(name).name());
    }

    @Test
    void aSeventeenCharacterNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> profile("abcdefghij0123456"));
    }

    @Test
    void anEmptyNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> profile(""));
    }

    @Test
    void anUppercaseLetterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> profile("Aggressive"));
    }

    @Test
    void aSpaceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> profile("my profile"));
    }

    @Test
    void digitsAndHyphensAreAccepted() {
        assertEquals("my-profile-2", profile("my-profile-2").name());
    }

    // --- ranges: exactly the settings' range(), not the slider ---

    @Test
    void targetRangeAcceptsTheFloorAndCeiling() {
        assertEquals(4, new PvpProfile("p", 4, 6, 12.0, ALL).targetRange());
        assertEquals(64, new PvpProfile("p", 64, 6, 12.0, ALL).targetRange());
    }

    @Test
    void targetRangeRejectsJustBelowAndAboveIt() {
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 3, 6, 12.0, ALL));
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 65, 6, 12.0, ALL));
    }

    @Test
    void approachDistanceAcceptsTheFloorAndCeiling() {
        assertEquals(2, new PvpProfile("p", 16, 2, 12.0, ALL).approachDistance());
        assertEquals(6, new PvpProfile("p", 16, 6, 12.0, ALL).approachDistance());
    }

    @Test
    void approachDistanceRejectsJustBelowAndAboveIt() {
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 16, 1, 12.0, ALL));
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 16, 7, 12.0, ALL));
    }

    @Test
    void threatMarginAcceptsTheFloorAndCeilingOfTheSettingRangeNotTheSlider() {
        assertEquals(0.0, new PvpProfile("p", 16, 6, 0.0, ALL).threatMargin());
        // the slider only goes to 20, but the setting's range() reaches 40 (design §2)
        assertEquals(40.0, new PvpProfile("p", 16, 6, 40.0, ALL).threatMargin());
    }

    @Test
    void threatMarginRejectsJustBelowAndAboveIt() {
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 16, 6, -0.001, ALL));
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 16, 6, 40.001, ALL));
    }

    // --- allowed modules ---

    @Test
    void anEmptyAllowedSetIsValid() {
        assertTrue(new PvpProfile("p", 16, 6, 12.0, Set.of()).allowed().isEmpty());
    }

    @Test
    void everyManagedModuleNameIsAccepted() {
        assertEquals(ALL, profile("p").allowed());
    }

    @Test
    void anUnmanagedModuleNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PvpProfile("p", 16, 6, 12.0, Set.of("auto-totem")));
    }

    @Test
    void hasCrystalAuraReflectsTheAllowedSet() {
        assertTrue(profile("p").hasCrystalAura());
        assertFalse(new PvpProfile("p", 16, 6, 12.0, Set.of()).hasCrystalAura());
    }

    // --- modified: exact for the ints and the set, 1e-6 tolerance for the margin ---

    @Test
    void identicalValuesAreNotModified() {
        assertFalse(profile("p").modified(16, 6, 12.0, ALL));
    }

    @Test
    void aDifferentTargetRangeIsModified() {
        assertTrue(profile("p").modified(17, 6, 12.0, ALL));
    }

    @Test
    void aDifferentApproachDistanceIsModified() {
        assertTrue(profile("p").modified(16, 5, 12.0, ALL));
    }

    @Test
    void aDifferentAllowedSetIsModified() {
        assertTrue(profile("p").modified(16, 6, 12.0, Set.of(ManagedModules.CRYSTAL_AURA.name())));
    }

    @Test
    void aMarginDifferenceWithinToleranceIsNotModified() {
        assertFalse(profile("p").modified(16, 6, 12.0 + 5e-7, ALL));
    }

    @Test
    void aMarginDifferenceBeyondToleranceIsModified() {
        assertTrue(profile("p").modified(16, 6, 12.0 + 2e-6, ALL));
    }
}
