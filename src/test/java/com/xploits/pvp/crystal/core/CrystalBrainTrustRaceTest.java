package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.player;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task T1: the health of a target reaches the client after the damage packet was read (the packet is queued on
 * the network thread, the health is applied on the main thread), so the pre-tick that counts a hit can still read
 * the old health. Only {@link CrystalBrain} wires the hit to {@link HealthTrust}, so the race is pinned here:
 * our crystal (id 900, 6 damage to the enemy) hurts the enemy at 20 health, and the drop (to 12.5, past the
 * required 3) shows some pre-ticks later.
 */
class CrystalBrainTrustRaceTest {
    private static final int CRYSTAL = 900;

    /** Pre-tick 1 places our crystal and reads the enemy at 20; it is added; the hit is handed over. Returns the next pre-tick. */
    private static long hitAt20(CrystalBrain b, CrystalSettings settings) {
        assertPlaces(2000L, b.preTick(settings, tick(1).targets(player(ENEMY, 3, 20)).candidates(spot(2000L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(2000L, 0);
        assertTrue(b.crystalAdded(settings, crystal(CRYSTAL, 2000L, Map.of(ENEMY, 6.0), 0.0), 20, HANDS).isEmpty());
        b.targetHurt(ENEMY, CRYSTAL);
        return 2;
    }

    private static void read(CrystalBrain b, CrystalSettings settings, long t, double health) {
        b.preTick(settings, tick(t).targets(player(ENEMY, 3, health)).build());
    }

    @Test
    void theDropArrivingAtTheCountingPreTickTrusts() {
        CrystalBrain b = new CrystalBrain();
        long t = hitAt20(b, DEFAULTS);
        read(b, DEFAULTS, t, 12.5);
        assertEquals(1, b.trustedTargetCount());
    }

    @Test
    void theRaceOneAndTwoAndThreePreTicksLateStillTrusts() {
        for (int late = 1; late <= 3; late++) {
            CrystalBrain b = new CrystalBrain();
            long t = hitAt20(b, DEFAULTS);
            for (int i = 0; i < late; i++) {
                read(b, DEFAULTS, t++, 20);
                assertEquals(0, b.trustedTargetCount(), "no verdict yet, late " + late);
            }
            read(b, DEFAULTS, t, 12.5);
            assertEquals(1, b.trustedTargetCount(), "late " + late);
        }
    }

    @Test
    void aDropFourPreTicksLateIsTooLateAndStaysUntrusted() {
        CrystalBrain b = new CrystalBrain();
        long t = hitAt20(b, DEFAULTS);
        for (int i = 0; i < 4; i++) read(b, DEFAULTS, t++, 20);
        read(b, DEFAULTS, t, 12.5);
        assertEquals(0, b.trustedTargetCount());
    }

    @Test
    void forgettingTheWindowsBetweenTheReadsLeavesTheHitUnjudged() {
        CrystalBrain b = new CrystalBrain();
        long t = hitAt20(b, DEFAULTS);
        read(b, DEFAULTS, t++, 20);
        b.forgetWindows();
        read(b, DEFAULTS, t, 12.5);
        assertEquals(0, b.trustedTargetCount(), "the pending hit is gone: the late drop trusts nothing");
    }

    @Test
    void withTheBudgetOffNothingIsEverTrusted() {
        CrystalBrain b = new CrystalBrain();
        long t = hitAt20(b, METEOR);
        read(b, METEOR, t, 12.5);
        assertEquals(0, b.trustedTargetCount());
    }
}
