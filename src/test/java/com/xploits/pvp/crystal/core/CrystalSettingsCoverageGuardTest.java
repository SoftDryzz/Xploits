package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No exposed setting ships untested: every constant of {@link CrystalSetting}, the one list the module builds
 * its settings from, must be named by a {@link Covers} test of the coverage matrix. The bench checks the other
 * half, that the module shows exactly this list ({@code capp-budget-off-parity}).
 */
class CrystalSettingsCoverageGuardTest {
    /** The coverage matrix: the test classes whose {@link Covers} tests count. */
    static final List<Class<?>> MATRIX = List.of(CrystalSettingsCoverageTest.class, CrystalSettingsPersistenceTest.class,
        MeteorParityPropertyTest.class);

    private static List<Method> coveringTests() {
        List<Method> tests = new ArrayList<>();
        for (Class<?> type : MATRIX) {
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Covers.class)) tests.add(m);
            }
        }
        return tests;
    }

    @Test
    void everyExposedSettingHasATestInTheMatrix() {
        Set<CrystalSetting> covered = EnumSet.noneOf(CrystalSetting.class);
        for (Method m : coveringTests()) covered.addAll(List.of(m.getAnnotation(Covers.class).value()));
        List<CrystalSetting> missing = Stream.of(CrystalSetting.values()).filter(s -> !covered.contains(s)).toList();
        assertEquals(List.of(), missing, "exposed settings with no test in the coverage matrix");
    }

    @Test
    void onlyATestCovers() {
        for (Method m : coveringTests()) {
            assertTrue(m.isAnnotationPresent(Test.class), m.getName() + " is marked as covering but is not a test");
            assertTrue(m.getAnnotation(Covers.class).value().length > 0, m.getName() + " covers nothing");
        }
    }

    @Test
    void theExposedSettingsAreTheSpecsInItsOrder() {
        // Spec §2, the names a player types and Meteor saves: never renamed.
        assertEquals(List.of("target-range", "min-damage", "max-damage", "anti-suicide", "rotate", "auto-switch",
                "no-gap-switch", "no-bow-switch", "anti-weakness", "swing-mode",
                "place", "place-range", "place-walls-range", "face-place", "face-place-health", "face-place-durability",
                "break", "break-range", "break-walls-range", "break-attempts", "attack-frequency", "fast-break",
                "pause-on-use", "pause-on-mine", "pause-on-lag", "pause-modules", "pause-health",
                "self-budget", "reserve", "safe-self-damage"),
            Stream.of(CrystalSetting.values()).map(CrystalSetting::id).toList());
        assertEquals(List.of("General", "Place", "Break", "Pause", "Safety"),
            Stream.of(CrystalSetting.Group.values()).map(CrystalSetting.Group::title).toList());
    }

    @Test
    void eachSettingHasItsOwnDescriptionAndNoDescriptionIsLeftOver() {
        Set<CrystalText> described = new HashSet<>();
        for (CrystalSetting s : CrystalSetting.values()) assertTrue(described.add(s.text()), s.id());
        for (CrystalText t : CrystalText.values()) {
            if (t.name().startsWith("SETTING_")) assertTrue(described.contains(t), t + " describes no exposed setting");
        }
    }
}
