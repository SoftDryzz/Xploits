package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read-only accessor the bench uses to tell our crystals from foreign ones. */
class CrystalBrainOwnCrystalsTest {
    @Test
    void listsOnlyCrystalsWePlaced() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.ownCrystals().isEmpty());
        assertPlaces(3000L, b.preTick(DEFAULTS, tick(1).candidates(spot(3000L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(3000L, 0);
        CrystalSettings noFastBreak = DEFAULTS.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, 0.0), 20, HANDS).isEmpty());
        // A crystal nobody of ours placed.
        assertTrue(b.crystalAdded(noFastBreak, crystal(951, 3001L, 6.0, 0.0), 20, HANDS).isEmpty());
        assertEquals(Map.of(950, false), b.ownCrystals());
    }
}
