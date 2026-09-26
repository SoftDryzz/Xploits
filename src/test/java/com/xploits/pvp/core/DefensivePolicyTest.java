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
        assertEquals(CombatPosture.CALM, DefensivePolicy.postureFor(self(20, 0, false, true)));
    }

    @Test
    void theDamageAlreadyAimedAtYouIsWhatDecides() {
        // §5: it is not "I am low on health", it is "what is already placed would leave me below the margin".
        // With full health and 10 damage aimed, 10 are left: below the margin.
        assertEquals(CombatPosture.THREATENED, DefensivePolicy.postureFor(self(20, 10, false, true)));
        assertEquals(CombatPosture.CALM, DefensivePolicy.postureFor(self(20, 5, false, true)),
            "the same damage with more slack is not a threat");
    }

    @Test
    void absorptionCountsBecauseTotalHealthIncludesIt() {
        // getTotalHealth() is health + absorption: with a golden apple on you, you are not threatened
        // even if health alone would end up below the margin.
        assertEquals(CombatPosture.CALM, DefensivePolicy.postureFor(self(28, 10, false, true)));
    }

    @Test
    void exactlyAtTheMarginIsAlreadyAThreat() {
        double atMargin = DefensivePolicy.THREAT_MARGIN;
        assertEquals(CombatPosture.THREATENED, DefensivePolicy.postureFor(self(atMargin, 0, false, true)));
        assertEquals(CombatPosture.CALM,
            DefensivePolicy.postureFor(self(Math.nextUp(atMargin), 0, false, true)));
    }

    @Test
    void theMarginIsTwelve() {
        // Pins the value, not only its existence: the other tests are expressed in terms of it.
        assertEquals(12.0, DefensivePolicy.THREAT_MARGIN, 0.0,
            "a crystal cycle and a half point-blank against enchanted netherite");
    }

    @Test
    void theMarginCanComeFromTheSetting() {
        // §5 leaves the threshold open and as a setting (threat-margin): THREAT_MARGIN is only the default
        // value. With the player's margin the comparison is the same, with another number.
        assertEquals(CombatPosture.CALM, DefensivePolicy.postureFor(self(20, 10, false, true), 4),
            "a short margin withstands what the default one already called a threat");
        assertEquals(CombatPosture.THREATENED, DefensivePolicy.postureFor(self(20, 2, false, true), 18),
            "and a long one trips earlier");
    }

    @Test
    void theDirectorPassesTheMarginThrough() {
        CombatSnapshot aimed = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, Map.of())
            .withDefense(20, 10, false, true);

        assertEquals(CombatPosture.THREATENED, new CombatDirector().tick(aimed, 6).posture());
        assertEquals(CombatPosture.CALM, new CombatDirector().tick(aimed, 6, 4).posture());
    }

    @Test
    void calmAsksForNothing() {
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.CALM, self(20, 0, true, true)).isEmpty(),
            "in a hole and calm, the surround is not enabled either");
    }

    @Test
    void threatenedAsksForTheFourThatDoNotImmobiliseYou() {
        List<ManagedModule> modules = DefensivePolicy.modulesFor(CombatPosture.THREATENED, self(10, 0, false, true));

        assertTrue(modules.contains(ManagedModules.HOLE_FILLER));
        assertTrue(modules.contains(ManagedModules.ANTI_ANVIL));
        assertTrue(modules.contains(ManagedModules.ANTI_BED));
        assertTrue(modules.contains(ManagedModules.ANTI_ANCHOR));
        assertEquals(4, modules.size(), "outside the hole there is no surround");
    }

    @Test
    void surroundOnlyInAHoleAndOnTheGround() {
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.THREATENED, self(10, 0, true, true))
            .contains(ManagedModules.SURROUND));
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.THREATENED, self(10, 0, true, false))
            .contains(ManagedModules.SURROUND), "without standing on the ground it re-centers and turns itself off in a loop");
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.THREATENED, self(10, 0, false, true))
            .contains(ManagedModules.SURROUND), "it is a defensive hole module");
    }

    @Test
    void surroundIsNotAskedForOnTheTickYouLandInTheHole() {
        // C2: Surround turns itself off with toggle-on-y-change (defaultValue(true)), and it checks it
        // in TickEvent.Pre with prevY != getY(), that is ONE TICK AFTER you moved
        // vertically. Landing in the hole leaves you on the ground and inside, so the two
        // earlier conditions asked for it anyway: the ledger turned it on and the module turned itself off
        // on the next tick, and that shutdown is indistinguishable from you turning it off.
        CombatSnapshot landing = self(10, 0, true, true).withSelfYChanged(true);
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.THREATENED, landing)
            .contains(ManagedModules.SURROUND),
            "while your Y moves, asking for it means turning it on so it turns itself off");

        CombatSnapshot settled = self(10, 0, true, true).withSelfYChanged(false);
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.THREATENED, settled)
            .contains(ManagedModules.SURROUND),
            "with your height still it is, which is when the surround is of any use");
    }

    @Test
    void theOtherFourDefensiveModulesDoNotCareAboutYourHeight() {
        // The condition belongs to surround and only to surround: the other four have no toggle-on-*.
        List<ManagedModule> moving = DefensivePolicy.modulesFor(CombatPosture.THREATENED,
            self(10, 0, true, true).withSelfYChanged(true));

        assertTrue(moving.contains(ManagedModules.HOLE_FILLER));
        assertTrue(moving.contains(ManagedModules.ANTI_ANVIL));
        assertTrue(moving.contains(ManagedModules.ANTI_BED));
        assertTrue(moving.contains(ManagedModules.ANTI_ANCHOR));
        assertEquals(4, moving.size());
    }

    @Test
    void theModulesThatLockYouInAreNotInTheCatalogueAtAll() {
        // §5: self-trap, self-web and burrow are left out on purpose. If the judgement is wrong, your
        // own client immobilizes you in a fight you were winning.
        for (ManagedModule module : ManagedModules.ALL) {
            assertFalse(List.of("self-trap", "self-web", "burrow").contains(module.name()),
                module.name() + " must not be in the catalog");
        }
    }

    @Test
    void theAntiModulesPlaceSomethingSoWithAnEmptyHotbarTheyStayOff() {
        // The three anti- modules react by placing a block: obsidian, string or a slab. With the inventory
        // at zero the posture still asks for them, but the resource filter keeps them off and says why.
        CombatSnapshot broke = Snapshots.of(false, 0, 0, 0, false, false, false, 0, Map.of())
            .withDefense(4, 0, false, true);
        Plan plan = new CombatDirector().tick(broke, 6);

        assertEquals(CombatPosture.THREATENED, plan.posture());
        for (ManagedModule module : List.of(ManagedModules.ANTI_ANVIL, ManagedModules.ANTI_BED,
                ManagedModules.ANTI_ANCHOR, ManagedModules.HOLE_FILLER)) {
            assertFalse(plan.enable().contains(module), module.name());
            assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(module)), module.name());
        }
    }
}
