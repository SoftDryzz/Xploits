package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    // --- The posture's memory (the owner's real fight, 2026-09-29) ---
    //
    // The damage already aimed at you is Meteor's possibleHealthReductions(): the strongest crystal that exists
    // this tick, whoever placed it. With two crystal auras placing and breaking every few ticks it comes and goes
    // with each crystal, and a posture that followed it flickered several times a second and turned the defensive
    // modules off between two crystals.

    private static final CombatSnapshot AIMED = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, Map.of())
        .withDefense(20, 10, false, true);
    private static final CombatSnapshot CLEAR = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, Map.of())
        .withDefense(20, 0, false, true);

    @Test
    void aThreatIsTakenAtOnce() {
        assertEquals(CombatPosture.THREATENED, new CombatDirector().tick(AIMED, 6).posture());
    }

    @Test
    void theCalmIsTakenOnlyAfterItHeldForTheWholeWait() {
        CombatDirector director = new CombatDirector();
        director.tick(AIMED, 6);
        for (int i = 1; i < CombatDirector.CALM_HOLD_TICKS; i++) {
            assertEquals(CombatPosture.THREATENED, director.tick(CLEAR, 6).posture(), "calm tick " + i);
        }
        assertEquals(CombatPosture.CALM, director.tick(CLEAR, 6).posture());
    }

    @Test
    void aThreatInsideTheWaitStartsItAgain() {
        CombatDirector director = new CombatDirector();
        director.tick(AIMED, 6);
        for (int i = 1; i < CombatDirector.CALM_HOLD_TICKS; i++) director.tick(CLEAR, 6);
        director.tick(AIMED, 6);
        for (int i = 1; i < CombatDirector.CALM_HOLD_TICKS; i++) {
            assertEquals(CombatPosture.THREATENED, director.tick(CLEAR, 6).posture(), "calm tick " + i + " after the second threat");
        }
        assertEquals(CombatPosture.CALM, director.tick(CLEAR, 6).posture());
    }

    @Test
    void crystalsComingAndGoingDoNotMakeThePostureFlicker() {
        CombatDirector director = new CombatDirector();
        List<CombatPosture> seen = new ArrayList<>();
        for (int i = 0; i < 40; i++) seen.add(director.tick(i % 3 == 0 ? AIMED : CLEAR, 6).posture());
        assertEquals(List.of(CombatPosture.THREATENED), seen.stream().distinct().toList());
    }

    @Test
    void theDefensiveModulesStayOnThroughTheWait() {
        CombatDirector director = new CombatDirector();
        assertTrue(director.tick(AIMED, 6).enable().contains(ManagedModules.ANTI_BED), "precondition");
        for (int i = 1; i < CombatDirector.CALM_HOLD_TICKS; i++) {
            assertTrue(director.tick(CLEAR, 6).enable().contains(ManagedModules.ANTI_BED), "calm tick " + i);
        }
        assertFalse(director.tick(CLEAR, 6).enable().contains(ManagedModules.ANTI_BED), "released once the calm held");
    }

    @Test
    void resetForgetsTheThreat() {
        CombatDirector director = new CombatDirector();
        director.tick(AIMED, 6);
        director.reset();
        assertEquals(CombatPosture.CALM, director.tick(CLEAR, 6).posture());
    }

    @Test
    void theCalmWaitIsThreeSeconds() {
        assertEquals(60, CombatDirector.CALM_HOLD_TICKS,
            "six crystal cycles at the invulnerability pace a crystal aura keeps: a real lull, not the gap between two");
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

    // --- task R3-13 fix 2: keep asking for surround when your hole has been breached (from the owner's
    // real log: an enemy breaks one side of the hole, isInHole(false) reads false on that same tick, and
    // surround stopped being requested exactly when the hole needed patching) ---

    /** Not in a hole any more, breached or not, with the rest at the values that used to ask for surround. */
    private static CombatSnapshot breached(boolean breached) {
        return self(10, 0, false, true).withHoleBreached(breached);
    }

    @Test
    void aBreachedHoleAsksForSurroundJustLikeBeingInOne() {
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.THREATENED, breached(true))
            .contains(ManagedModules.SURROUND),
            "selfInHole already reads false the tick the breach happens: without this, surround stops "
                + "being requested on exactly the tick it is needed to patch the gap");
    }

    @Test
    void withoutTheBreachFactThereIsNoSurroundOutsideTheHole() {
        // What the adapter reports when the block was mined somewhere else, or long enough ago, or you
        // walked off the block: none of that is this rule's business, it only reads the one fact.
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.THREATENED, breached(false))
            .contains(ManagedModules.SURROUND));
    }

    @Test
    void aBreachedHoleStillNeedsTheGroundAndStillHeightToAskForSurround() {
        // The breach does not bypass the other two guards (§5, critical C2): they are ANDed with the
        // whole (selfInHole || selfHoleBreached), not only with selfInHole.
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.THREATENED,
                self(10, 0, false, false).withHoleBreached(true))
            .contains(ManagedModules.SURROUND), "off the ground, breached or not, it still re-centers in a loop");
        assertFalse(DefensivePolicy.modulesFor(CombatPosture.THREATENED,
                breached(true).withSelfYChanged(true))
            .contains(ManagedModules.SURROUND), "your height just moved: the same self-shutdown guard as selfInHole");
    }

    @Test
    void aCalmPostureAsksForNothingEvenWhenBreached() {
        assertTrue(DefensivePolicy.modulesFor(CombatPosture.CALM, breached(true)).isEmpty());
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
    void withAnEmptyHotbarOnlyAntiBedStaysUp() {
        // anti-anvil and anti-anchor only react by placing a block (obsidian, a slab): with the inventory
        // at zero the posture still asks for them, but the resource filter keeps them off and says why.
        // anti-bed also breaks a bed already on your head with no item, so it still comes up.
        CombatSnapshot broke = Snapshots.of(false, 0, 0, 0, false, false, false, 0, Map.of())
            .withDefense(4, 0, false, true);
        Plan plan = new CombatDirector().tick(broke, 6);

        assertEquals(CombatPosture.THREATENED, plan.posture());
        for (ManagedModule module : List.of(ManagedModules.ANTI_ANVIL, ManagedModules.ANTI_ANCHOR,
                ManagedModules.HOLE_FILLER)) {
            assertFalse(plan.enable().contains(module), module.name());
            assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(module)), module.name());
        }
        assertTrue(plan.enable().contains(ManagedModules.ANTI_BED));
    }
}
