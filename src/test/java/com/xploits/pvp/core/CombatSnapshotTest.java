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
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, 0, false, false, false, 2,
            Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, 5));

        assertEquals(12, snapshot.amountOf(Resource.CRYSTALS));
        assertEquals(5, snapshot.amountOf(Resource.OBSIDIAN));
        assertEquals(0, snapshot.amountOf(Resource.WEBS));
    }

    @Test
    void theSnapshotCopiesItsResourcesSoLaterChangesDoNotLeakIn() {
        Map<Resource, Integer> resources = new HashMap<>();
        resources.put(Resource.CRYSTALS, 12);
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, 0, false, false, false, 2, resources);

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
        // El andamio para que el adaptador siga compilando mientras se le añade la lectura de los
        // campos nuevos: los rellena con lo neutro, no con lo real, para que los dos ejes nuevos se
        // comporten como si no existieran hasta que alguien los rellene de verdad.
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, 0, false, false, false, 2, Map.of());

        assertNull(snapshot.targetId());
        assertEquals(0, snapshot.unprotectedHostilesInCrystalRange());
        assertEquals(CombatSnapshot.FULL_HEALTH, snapshot.selfTotalHealth(), 0.0);
        assertEquals(0.0, snapshot.incomingDamage(), 0.0);
        assertFalse(snapshot.selfInHole());
        assertFalse(snapshot.selfOnGround());
        assertEquals(CombatPosture.TRANQUILO, DefensivePolicy.postureFor(snapshot));
    }

    @Test
    void theHelpersChangeOnlyWhatTheyName() {
        CombatSnapshot base = new CombatSnapshot(true, 3.0, false, 0, false, false, false, 2,
            Map.of(Resource.CRYSTALS, 12));

        assertEquals("pepe", base.withTargetId("pepe").targetId());
        assertEquals(3.0, base.withTargetId("pepe").targetDistance(), 0.0);

        assertEquals(2, base.withHostiles(2).unprotectedHostilesInCrystalRange());
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
        // La tabla de la spec §6, ampliada con el eje defensivo del rediseño §5.
        assertEquals(Resource.CRYSTALS, ManagedModules.CRYSTAL_AURA.needs());
        assertEquals(Resource.OBSIDIAN, ManagedModules.AUTO_TRAP.needs());
        assertEquals(Resource.OBSIDIAN, ManagedModules.SURROUND.needs());
        assertEquals(Resource.WEBS, ManagedModules.AUTO_WEB.needs());
        assertEquals(Resource.ANVILS, ManagedModules.AUTO_ANVIL.needs());
        assertEquals(Resource.PICKAXE, ManagedModules.AUTO_CITY.needs());
        assertEquals(Resource.OBSIDIAN, ManagedModules.HOLE_FILLER.needs());
        assertEquals(Resource.NONE, ManagedModules.ANTI_ANVIL.needs());
        assertEquals(Resource.NONE, ManagedModules.ANTI_BED.needs());
        assertEquals(Resource.NONE, ManagedModules.ANTI_ANCHOR.needs());

        // La marca turnsItselfOff() (spec §7): crystal-aura, auto-web y los cuatro defensivos solo
        // se apagan porque el jugador los apaga; los otros cuatro se apagan solos con los ajustes
        // de fábrica de Meteor. Sin esto, cambiar la marca de cualquiera no lo detecta ningún test.
        assertFalse(ManagedModules.CRYSTAL_AURA.turnsItselfOff());
        assertTrue(ManagedModules.AUTO_TRAP.turnsItselfOff());
        assertFalse(ManagedModules.AUTO_WEB.turnsItselfOff());
        assertTrue(ManagedModules.SURROUND.turnsItselfOff());
        assertTrue(ManagedModules.AUTO_ANVIL.turnsItselfOff());
        assertTrue(ManagedModules.AUTO_CITY.turnsItselfOff());
        assertFalse(ManagedModules.HOLE_FILLER.turnsItselfOff());
        assertFalse(ManagedModules.ANTI_ANVIL.turnsItselfOff());
        assertFalse(ManagedModules.ANTI_BED.turnsItselfOff());
        assertFalse(ManagedModules.ANTI_ANCHOR.turnsItselfOff());

        for (ManagedModule module : ManagedModules.ALL) {
            assertFalse(module.name().isBlank(), "el módulo debe tener nombre");
            boolean free = module.needs() == Resource.NONE;
            assertEquals(free, module.minimum() == 0,
                module.name() + ": solo los que no gastan nada pueden pedir cero");
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
