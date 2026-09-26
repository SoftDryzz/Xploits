package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xploits.pvp.crystal.core.RiskLevel.AGGRESSIVE;
import static com.xploits.pvp.crystal.core.RiskLevel.BALANCED;
import static com.xploits.pvp.crystal.core.RiskLevel.SAFE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which scenarios a bench run plays (R3-9): the everyday run skips crystal-aura++'s Safe and Aggressive
 * MEASUREs, the full run plays everything, and {@code -Pbench.only} wins over both.
 */
class ProfileTest {
    @Test
    void theDefaultIsTheEverydayRun() {
        assertEquals(Profile.EVERYDAY, Profile.of(false, false));
    }

    @Test
    void benchFullIsTheFullRun() {
        assertEquals(Profile.FULL, Profile.of(true, false));
    }

    @Test
    void benchOnlyWinsOverBoth() {
        assertEquals(Profile.PARTIAL, Profile.of(false, true));
        assertEquals(Profile.PARTIAL, Profile.of(true, true));
    }

    @Test
    void theEverydayRunPlaysEveryCheck() {
        // A CHECK always runs, even one that plays crystal-aura++ at a level the everyday run does not measure.
        assertTrue(Profile.EVERYDAY.plays(false, null));
        for (RiskLevel level : RiskLevel.values()) assertTrue(Profile.EVERYDAY.plays(false, level), level.name());
    }

    @Test
    void theEverydayRunPlaysMeteorsMeasuresAndBalancedOnly() {
        // ca-* and defense-attacker have no level; capp-balanced-* runs at Balanced.
        assertTrue(Profile.EVERYDAY.plays(true, null));
        assertTrue(Profile.EVERYDAY.plays(true, BALANCED));
        // capp-* at Safe and capp-aggressive-* are experimental: only the full run measures them.
        assertFalse(Profile.EVERYDAY.plays(true, SAFE));
        assertFalse(Profile.EVERYDAY.plays(true, AGGRESSIVE));
        assertFalse(Profile.EVERYDAY.plays(true, RiskLevel.CUSTOM));
    }

    @Test
    void theFullAndPartialRunsPlayEverything() {
        for (Profile profile : List.of(Profile.FULL, Profile.PARTIAL)) {
            assertTrue(profile.plays(true, null));
            assertTrue(profile.plays(false, null));
            for (RiskLevel level : RiskLevel.values()) {
                assertTrue(profile.plays(true, level), profile + " " + level);
                assertTrue(profile.plays(false, level), profile + " " + level);
            }
        }
    }

    @Test
    void theEverydayRunDoesNotMeasureSafeNorAggressive() {
        assertEquals(List.of(SAFE, AGGRESSIVE), Profile.EVERYDAY.notMeasured());
        assertEquals(List.of(), Profile.FULL.notMeasured());
        assertEquals(List.of(), Profile.PARTIAL.notMeasured());
    }

    @Test
    void eachProfileSaysWhichRunItIs() {
        assertEquals("everyday run", Profile.EVERYDAY.label());
        assertEquals("full run", Profile.FULL.label());
        assertEquals("partial run", Profile.PARTIAL.label());
        assertEquals("everyday", Profile.EVERYDAY.id());
        assertEquals("full", Profile.FULL.id());
        assertEquals("partial", Profile.PARTIAL.id());
    }

    @Test
    void theSkippedLineNamesTheFullRun() {
        assertEquals("not in the everyday run (use -Pbench.full)", Profile.EVERYDAY.skipped());
    }
}
