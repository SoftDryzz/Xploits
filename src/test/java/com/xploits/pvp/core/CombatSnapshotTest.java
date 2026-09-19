package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombatSnapshotTest {
    @Test
    void anUnknownResourceIsZeroNotAnError() {
        assertEquals(0, CombatSnapshot.none().amountOf(Resource.CRYSTALS));
    }

    @Test
    void itReportsTheResourcesItWasGiven() {
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, false, false, false, 2,
            Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, 5));

        assertEquals(12, snapshot.amountOf(Resource.CRYSTALS));
        assertEquals(5, snapshot.amountOf(Resource.OBSIDIAN));
        assertEquals(0, snapshot.amountOf(Resource.WEBS));
    }

    @Test
    void theSnapshotCopiesItsResourcesSoLaterChangesDoNotLeakIn() {
        Map<Resource, Integer> resources = new HashMap<>();
        resources.put(Resource.CRYSTALS, 12);
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, false, false, false, 2, resources);

        resources.put(Resource.CRYSTALS, 999);

        assertEquals(12, snapshot.amountOf(Resource.CRYSTALS));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().put(Resource.WEBS, 1));
    }

    @Test
    void noneHasNoTarget() {
        assertFalse(CombatSnapshot.none().hasTarget());
    }

    @Test
    void everyManagedModuleDeclaresWhatItNeeds() {
        for (ManagedModule module : ManagedModules.ALL) {
            assertFalse(module.name().isBlank(), "el módulo debe tener nombre");
            assertEquals(true, module.minimum() >= 1, module.name() + " debe pedir al menos 1");
        }
        assertEquals(6, ManagedModules.ALL.size());
    }

    @Test
    void theManagedModulesAreTheOnesMeteorActuallyHas() {
        assertEquals("crystal-aura", ManagedModules.CRYSTAL_AURA.name());
        assertEquals("auto-trap", ManagedModules.AUTO_TRAP.name());
        assertEquals("auto-web", ManagedModules.AUTO_WEB.name());
        assertEquals("surround", ManagedModules.SURROUND.name());
        assertEquals("auto-anvil", ManagedModules.AUTO_ANVIL.name());
        assertEquals("auto-city", ManagedModules.AUTO_CITY.name());
    }
}
