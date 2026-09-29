package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertBreaks;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.dealing;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B1: with the self-budget on, the reserve (not Meteor's max-damage, 6 by default) bounds the self damage
 * of OUR placements and breaks; anti-suicide stays; foreign crystals keep max-damage; budget off is Meteor.
 * Balanced reserve is 3.5. Every number is exact in binary.
 */
class CrystalBrainReserveDecidesTest {
    /** Makes {@code c} ours the way it happens in game: placed at full health, then it appears. */
    private static CrystalBrain ownCrystal(CrystalSeen c) {
        CrystalBrain b = new CrystalBrain();
        assertPlaces(c.pos(), b.preTick(DEFAULTS, tick(1).health(20).candidates(spot(c.pos(), 8, 0)).build()));
        b.placed(c.pos(), 0);
        assertTrue(b.crystalAdded(dealing(c, 0), 20, HANDS).isEmpty());
        return b;
    }

    @Test
    void anOwnSpotPastMaxDamageIsPlacedWhenTheReserveAllows() {
        // 20 - 6.5 = 13.5 >= 3.5
        assertPlaces(7, new CrystalBrain().preTick(DEFAULTS, tick(1).health(20).candidates(spot(7, 8, 6.5)).build()));
    }

    @Test
    void anOwnSpotTheReserveRefusesIsStillRefused() {
        // 9 - 6.5 = 2.5 < 3.5
        assertNothing(new CrystalBrain().preTick(DEFAULTS, tick(1).health(9).candidates(spot(7, 8, 6.5)).build()));
    }

    /** Documentation of an equivalent mutant: it cannot fail on a real bug, see the note inside. */
    @Test
    void antiSuicideStillRefusesAnOwnSpotThatWouldKill() {
        // Also refused by the reserve (the budget never reads less than Meteor's figure), so anti-suicide is a
        // second wall here: removing it changes nothing observable through the brain (equivalent mutation).
        assertNothing(new CrystalBrain().preTick(DEFAULTS, tick(1).health(8).candidates(spot(7, 8, 8)).build()));
    }

    @Test
    void budgetOffMaxDamageStillBindsPlacements() {
        assertNothing(new CrystalBrain().preTick(METEOR, tick(1).health(20).candidates(spot(7, 8, 6.5)).build()));
    }

    @Test
    void anOwnCrystalPastMaxDamageIsBrokenWithinTheFloor() {
        CrystalSeen mine = crystal(1, 8, 6.5);
        CrystalBrain b = ownCrystal(mine);
        // 20 - 0 - 6.5 = 13.5 >= 2
        assertBreaks(1, b.preTick(DEFAULTS, tick(2).health(20).crystals(mine).build()));
    }

    @Test
    void anOwnCrystalPastMaxDamageBelowTheFloorIsNotBroken() {
        CrystalSeen mine = crystal(1, 8, 6.5);
        CrystalBrain b = ownCrystal(mine);
        // 8 - 0 - 6.5 = 1.5 < 2
        assertNothing(b.preTick(DEFAULTS, tick(2).health(8).crystals(mine).build()));
    }

    /** Documentation of an equivalent mutant: the reserve refuses first, so removing anti-suicide changes nothing observable. */
    @Test
    void antiSuicideStillRefusesAnOwnBreakThatWouldKill() {
        CrystalSeen mine = crystal(1, 8, 8);
        CrystalBrain b = ownCrystal(mine);
        assertNothing(b.preTick(DEFAULTS, tick(2).health(8).crystals(mine).build()));
    }

    @Test
    void aForeignCrystalPastMaxDamageIsNotBroken() {
        assertNothing(new CrystalBrain().preTick(DEFAULTS, tick(1).health(20).crystals(crystal(1, 8, 6.5)).build()));
        // Control: within max-damage the same foreign crystal is broken.
        assertBreaks(1, new CrystalBrain().preTick(DEFAULTS, tick(1).health(20).crystals(crystal(1, 8, 5)).build()));
    }

    @Test
    void fastBreakOfAForeignCrystalPastMaxDamageStaysUnbroken() {
        CrystalBrain over = new CrystalBrain();
        assertNothing(over.preTick(DEFAULTS, tick(1).health(20).build()));
        assertTrue(over.crystalAdded(DEFAULTS, crystal(1, 8, 6.5), 20, HANDS).isEmpty());
        // Control: within max-damage the same foreign crystal is fast-broken.
        CrystalBrain within = new CrystalBrain();
        assertNothing(within.preTick(DEFAULTS, tick(1).health(20).build()));
        assertTrue(within.crystalAdded(DEFAULTS, crystal(1, 8, 5), 20, HANDS).isPresent());
    }

    @Test
    void budgetOffMaxDamageStillBindsBreaks() {
        CrystalSeen mine = crystal(1, 8, 6.5);
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(METEOR, tick(1).health(20).crystals(mine).build()));
    }
}
