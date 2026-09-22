package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefensivePolicyTest {
    private static CombatSnapshot self(double totalHealth, double incoming, boolean inHole, boolean onGround) {
        return CombatSnapshot.none().withDefense(totalHealth, incoming, inHole, onGround);
    }

    @Test
    void fullHealthWithNothingAimedAtYouIsCalm() {
        assertEquals(CombatPosture.TRANQUILO, DefensivePolicy.postureFor(self(20, 0, false, true)));
    }

    @Test
    void theDamageAlreadyAimedAtYouIsWhatDecides() {
        // §5: no es "estoy bajo de vida", es "lo que ya está colocado me dejaría bajo el margen".
        // Con la vida llena y 10 de daño apuntando, quedan 10: por debajo del margen.
        assertEquals(CombatPosture.AMENAZADO, DefensivePolicy.postureFor(self(20, 10, false, true)));
        assertEquals(CombatPosture.TRANQUILO, DefensivePolicy.postureFor(self(20, 5, false, true)),
            "el mismo daño con más holgura no es amenaza");
    }

    @Test
    void absorptionCountsBecauseTotalHealthIncludesIt() {
        // getTotalHealth() es vida + absorción: con una manzana de oro encima no estás amenazado
        // aunque la vida sola quedara por debajo del margen.
        assertEquals(CombatPosture.TRANQUILO, DefensivePolicy.postureFor(self(28, 10, false, true)));
    }

    @Test
    void exactlyAtTheMarginIsAlreadyAThreat() {
        double atMargin = DefensivePolicy.THREAT_MARGIN;
        assertEquals(CombatPosture.AMENAZADO, DefensivePolicy.postureFor(self(atMargin, 0, false, true)));
        assertEquals(CombatPosture.TRANQUILO,
            DefensivePolicy.postureFor(self(Math.nextUp(atMargin), 0, false, true)));
    }

    @Test
    void theMarginIsTwelve() {
        // Fija el valor, no solo su existencia: los demás tests se expresan en función de él.
        assertEquals(12.0, DefensivePolicy.THREAT_MARGIN, 0.0,
            "ciclo y medio de cristal a bocajarro contra netherita encantada");
    }

    @Test
    void theMarginCanComeFromTheSetting() {
        // §5 deja el umbral abierto y como ajuste (threat-margin): THREAT_MARGIN es solo el valor
        // de fábrica. Con el margen del jugador la comparación es la misma, con otro número.
        assertEquals(CombatPosture.TRANQUILO, DefensivePolicy.postureFor(self(20, 10, false, true), 4),
            "un margen corto aguanta lo que el de fábrica ya llamaba amenaza");
        assertEquals(CombatPosture.AMENAZADO, DefensivePolicy.postureFor(self(20, 2, false, true), 18),
            "y uno largo salta antes");
    }

    @Test
    void theDirectorPassesTheMarginThrough() {
        CombatSnapshot aimed = Snapshots.of(true, 3.0, false, 0, false, false, false, 2, Map.of())
            .withDefense(20, 10, false, true);

        assertEquals(CombatPosture.AMENAZADO, new CombatDirector().tick(aimed, 6).posture());
        assertEquals(CombatPosture.TRANQUILO, new CombatDirector().tick(aimed, 6, 4).posture());
    }

    @Test
    void calmAsksForNothing() {
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.TRANQUILO, self(20, 0, true, true)).isEmpty(),
            "en un agujero y tranquilo tampoco se enciende el surround");
    }

    @Test
    void threatenedAsksForTheFourThatDoNotImmobiliseYou() {
        List<ManagedModule> modules = DefensivePolicy.modulesFor(CombatPosture.AMENAZADO, self(10, 0, false, true));

        assertTrue(modules.contains(ManagedModules.HOLE_FILLER));
        assertTrue(modules.contains(ManagedModules.ANTI_ANVIL));
        assertTrue(modules.contains(ManagedModules.ANTI_BED));
        assertTrue(modules.contains(ManagedModules.ANTI_ANCHOR));
        assertEquals(4, modules.size(), "fuera del agujero no hay surround");
    }

    @Test
    void surroundOnlyInAHoleAndOnTheGround() {
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.AMENAZADO, self(10, 0, true, true))
            .contains(ManagedModules.SURROUND));
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.AMENAZADO, self(10, 0, true, false))
            .contains(ManagedModules.SURROUND), "sin pisar suelo se recoloca y se apaga solo en bucle");
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.AMENAZADO, self(10, 0, false, true))
            .contains(ManagedModules.SURROUND), "es un módulo defensivo de agujero");
    }

    @Test
    void theModulesThatLockYouInAreNotInTheCatalogueAtAll() {
        // §5: self-trap, self-web y burrow quedan fuera a propósito. Si el criterio se equivoca, te
        // inmoviliza tu propio cliente en una pelea que ibas ganando.
        for (ManagedModule module : ManagedModules.ALL) {
            assertFalse(List.of("self-trap", "self-web", "burrow").contains(module.name()),
                module.name() + " no debe estar en el catálogo");
        }
    }

    @Test
    void theAntiModulesCostNothingSoNoShortageCanRemoveThem() {
        // Los tres anti- no colocan: escuchan y reaccionan. Con el inventario a cero siguen subiendo.
        CombatSnapshot broke = Snapshots.of(false, 0, false, 0, false, false, false, 0, Map.of())
            .withDefense(4, 0, false, true);
        Plan plan = new CombatDirector().tick(broke, 6);

        assertEquals(CombatPosture.AMENAZADO, plan.posture());
        assertTrue(plan.enable().contains(ManagedModules.ANTI_ANVIL));
        assertTrue(plan.enable().contains(ManagedModules.ANTI_BED));
        assertTrue(plan.enable().contains(ManagedModules.ANTI_ANCHOR));
        assertFalse(plan.enable().contains(ManagedModules.HOLE_FILLER), "el hole-filler sí coloca bloques");
    }
}
