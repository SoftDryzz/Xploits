package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatSnapshotTest {
    @Test
    void anUnknownResourceIsZeroNotAnError() {
        assertEquals(0, CombatSnapshot.none().amountOf(Resource.CRYSTALS));
    }

    @Test
    void itReportsTheResourcesItWasGiven() {
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2,
            Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, 5));

        assertEquals(12, snapshot.amountOf(Resource.CRYSTALS));
        assertEquals(5, snapshot.amountOf(Resource.OBSIDIAN));
        assertEquals(0, snapshot.amountOf(Resource.WEBS));
    }

    @Test
    void theSnapshotCopiesItsResourcesSoLaterChangesDoNotLeakIn() {
        Map<Resource, Integer> resources = new HashMap<>();
        resources.put(Resource.CRYSTALS, 12);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources);

        resources.put(Resource.CRYSTALS, 999);

        assertEquals(12, snapshot.amountOf(Resource.CRYSTALS));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().put(Resource.WEBS, 1));
    }

    @Test
    void noneHasNoTarget() {
        assertFalse(CombatSnapshot.none().hasTarget());
    }

    @Test
    void theTransitionalConstructorFillsTheNewFieldsWithNeutralValues() {
        // The scaffolding so the adapter keeps compiling while the reading of the new fields is
        // added to it: it fills them with the neutral values, not the real ones, so the two new axes
        // behave as if they did not exist until someone really fills them in.
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, Map.of());

        assertNull(snapshot.targetId());
        assertEquals(0, snapshot.hostilesInCrystalRange());
        assertEquals(CombatSnapshot.FULL_HEALTH, snapshot.selfTotalHealth(), 0.0);
        assertEquals(0.0, snapshot.incomingDamage(), 0.0);
        assertFalse(snapshot.selfInHole());
        assertFalse(snapshot.selfOnGround());
        assertEquals(CombatPosture.CALM, DefensivePolicy.postureFor(snapshot));
    }

    @Test
    void theHelpersChangeOnlyWhatTheyName() {
        CombatSnapshot base = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2,
            Map.of(Resource.CRYSTALS, 12));

        assertEquals("pepe", base.withTargetId("pepe").targetId());
        assertEquals(3.0, base.withTargetId("pepe").targetDistance(), 0.0);

        assertEquals(2, base.withHostiles(2).hostilesInCrystalRange());
        assertEquals(12, base.withHostiles(2).amountOf(Resource.CRYSTALS));

        CombatSnapshot defended = base.withDefense(10, 4, true, true);
        assertEquals(10, defended.selfTotalHealth(), 0.0);
        assertEquals(4, defended.incomingDamage(), 0.0);
        assertTrue(defended.selfInHole());
        assertTrue(defended.selfOnGround());
        assertEquals(3.0, defended.targetDistance(), 0.0);

        assertEquals(7.0, base.withTargetDistance(7.0).targetDistance(), 0.0);
        assertEquals(2, base.withTargetDistance(7.0).selfTotems());
    }

    @Test
    void everyManagedModuleDeclaresWhatItNeeds() {
        // The table of spec §6, extended with the defensive axis of redesign §5.
        assertEquals(Resource.CRYSTALS, ManagedModules.CRYSTAL_AURA.needs());
        assertEquals(Resource.OBSIDIAN, ManagedModules.AUTO_TRAP.needs());
        assertEquals(Resource.OBSIDIAN, ManagedModules.SURROUND.needs());
        assertEquals(Resource.WEBS, ManagedModules.AUTO_WEB.needs());
        assertEquals(Resource.ANVILS, ManagedModules.AUTO_ANVIL.needs());
        assertEquals(Resource.PICKAXE, ManagedModules.AUTO_CITY.needs());
        assertEquals(Resource.OBSIDIAN, ManagedModules.HOLE_FILLER.needs());
        // The three anti- modules do place something, checked in the meteor-client 1.21.11 sources:
        // AntiAnvil obsidian, AntiBed string and AntiAnchor any slab, all from the hotbar.
        assertEquals(Resource.OBSIDIAN, ManagedModules.ANTI_ANVIL.needs());
        assertEquals(Resource.STRING, ManagedModules.ANTI_BED.needs());
        assertEquals(Resource.SLABS, ManagedModules.ANTI_ANCHOR.needs());

        // The turnsItselfOff() flag (spec §7): crystal-aura, auto-web and the five defensive ones
        // -surround included since C2- only turn off because the player turns them off; the other three
        // turn themselves off with Meteor's default settings. Without this, changing the flag of
        // any of them is caught by no test.
        assertFalse(ManagedModules.CRYSTAL_AURA.turnsItselfOff());
        assertTrue(ManagedModules.AUTO_TRAP.turnsItselfOff());
        assertFalse(ManagedModules.AUTO_WEB.turnsItselfOff());
        assertFalse(ManagedModules.SURROUND.turnsItselfOff(),
            "C2: with the flag set the debounce did not apply to it and you could not turn it off by hand");
        assertTrue(ManagedModules.AUTO_ANVIL.turnsItselfOff());
        assertTrue(ManagedModules.AUTO_CITY.turnsItselfOff());
        assertFalse(ManagedModules.HOLE_FILLER.turnsItselfOff());
        assertFalse(ManagedModules.ANTI_ANVIL.turnsItselfOff());
        assertFalse(ManagedModules.ANTI_BED.turnsItselfOff());
        assertFalse(ManagedModules.ANTI_ANCHOR.turnsItselfOff());

        // reactive(): only the three anti- modules spend only when their threat shows up.
        for (ManagedModule module : ManagedModules.ALL) {
            assertFalse(module.name().isBlank(), "the module must have a name");
            assertTrue(module.minimum() >= 1, module.name() + ": every managed module places or mines something");
            assertEquals(module.name().startsWith("anti-"), module.reactive(), module.name());
        }
        assertEquals(10, ManagedModules.ALL.size());
    }

    @Test
    void theManagedModulesAreTheOnesMeteorActuallyHas() {
        assertEquals("crystal-aura", ManagedModules.CRYSTAL_AURA.name());
        assertEquals("auto-trap", ManagedModules.AUTO_TRAP.name());
        assertEquals("auto-web", ManagedModules.AUTO_WEB.name());
        assertEquals("surround", ManagedModules.SURROUND.name());
        assertEquals("auto-anvil", ManagedModules.AUTO_ANVIL.name());
        assertEquals("auto-city", ManagedModules.AUTO_CITY.name());
        assertEquals("hole-filler", ManagedModules.HOLE_FILLER.name());
        assertEquals("anti-anvil", ManagedModules.ANTI_ANVIL.name());
        assertEquals("anti-bed", ManagedModules.ANTI_BED.name());
        assertEquals("anti-anchor", ManagedModules.ANTI_ANCHOR.name());
    }
}
