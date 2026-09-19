package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDirectorTest {
    private static final int APPROACH = 6;

    private static final Map<Resource, Integer> FULL = Map.of(
        Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64,
        Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);

    /** Enemigo cerca, a pie, limpio, con todo el equipo encima. */
    private static CombatSnapshot surface() {
        return new CombatSnapshot(true, 3.0, false, false, false, false, 2, FULL);
    }

    private static CombatSnapshot with(CombatSnapshot base, boolean surrounded, boolean burrowed,
                                       boolean targetGliding, boolean selfGliding) {
        return new CombatSnapshot(base.hasTarget(), base.targetDistance(), surrounded, burrowed,
            targetGliding, selfGliding, base.selfTotems(), base.resources());
    }

    /** Deja que el director se asiente en la fase que pide el snapshot. */
    private static Plan settle(CombatDirector director, CombatSnapshot snapshot) {
        Plan plan = null;
        for (int i = 0; i < CombatDirector.MIN_DWELL_TICKS + CombatDirector.CHANGE_HOLD_TICKS + 1; i++) {
            plan = director.tick(snapshot, APPROACH);
        }
        return plan;
    }

    private static boolean enables(Plan plan, ManagedModule module) {
        return plan.enable().contains(module);
    }

    @Test
    void withoutATargetItIsOutOfCombatAndAsksForNothing() {
        Plan plan = new CombatDirector().tick(CombatSnapshot.none(), APPROACH);
        assertEquals(CombatState.SIN_COMBATE, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void losingTheTargetDropsEverythingOnTheVeryNextTickWithoutWaiting() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = director.tick(CombatSnapshot.none(), APPROACH);

        assertEquals(CombatState.SIN_COMBATE, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void aTargetNearAndOnFootIsSurfaceWithItsThreeModules() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(enables(plan, ManagedModules.AUTO_WEB));
    }

    @Test
    void aTargetFurtherThanTheApproachDistanceIsApproach() {
        CombatSnapshot far = new CombatSnapshot(true, 7.0, false, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), far);
        assertEquals(CombatState.ACERCAMIENTO, plan.state());
        assertTrue(enables(plan, ManagedModules.SURROUND));
    }

    @Test
    void justInsideTheApproachDistanceIsSurface() {
        CombatSnapshot near = new CombatSnapshot(true, 5.0, false, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), near).state());
    }

    @Test
    void aSurroundedTargetCallsForAutoCity() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, false, false, false));
        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void aBurrowedTargetCallsForAnvilsAndTurnsTheCrystalsOff() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false));
        assertEquals(CombatState.ENTERRADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA), "contra un enterrado los cristales no sirven");
    }

    @Test
    void burrowedBeatsSurroundedWhenBothAreTrue() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, true, false, false));
        assertEquals(CombatState.ENTERRADO, plan.state());
    }

    @Test
    void aGlidingTargetIsAChase() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, false, true, false));
        assertEquals(CombatState.PERSECUCION, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_WEB));
    }

    @Test
    void youGlidingIsAChaseToo() {
        assertEquals(CombatState.PERSECUCION,
            settle(new CombatDirector(), with(surface(), false, false, false, true)).state());
    }

    @Test
    void chaseBeatsBurrowedWhenBothAreTrue() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, true, false));
        assertEquals(CombatState.PERSECUCION, plan.state());
    }

    @Test
    void aConditionThatDoesNotHoldLongEnoughDoesNotChangeTheState() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < CombatDirector.CHANGE_HOLD_TICKS - 1; i++) director.tick(burrowed, APPROACH);

        assertEquals(CombatState.SUPERFICIE, director.state());
    }

    @Test
    void aConditionThatHoldsLongEnoughDoesChangeTheState() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < CombatDirector.CHANGE_HOLD_TICKS; i++) director.tick(burrowed, APPROACH);

        assertEquals(CombatState.ENTERRADO, director.state());
    }

    @Test
    void aFlickeringConditionNeverAccumulatesEnoughToChange() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < 100; i++) {
            director.tick(i % 2 == 0 ? burrowed : surface(), APPROACH);
        }

        assertEquals(CombatState.SUPERFICIE, director.state());
    }

    @Test
    void aFreshlyEnteredStateIsNotAbandonedBeforeTheDwellTime() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        // Entra en ENTERRADO, que deja el contador de permanencia a cero.
        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < CombatDirector.CHANGE_HOLD_TICKS; i++) director.tick(burrowed, APPROACH);
        assertEquals(CombatState.ENTERRADO, director.state());

        // Recién entrado, no debe abandonarla aunque la nueva condición se mantenga.
        CombatSnapshot surrounded = with(surface(), true, false, false, false);
        for (int i = 0; i < CombatDirector.CHANGE_HOLD_TICKS; i++) director.tick(surrounded, APPROACH);

        assertEquals(CombatState.ENTERRADO, director.state(), "aún no ha cumplido la permanencia mínima");
    }

    @Test
    void engagingDoesNotWaitForTheDwellTime() {
        CombatDirector director = new CombatDirector();
        for (int i = 0; i < CombatDirector.CHANGE_HOLD_TICKS; i++) director.tick(surface(), APPROACH);

        assertEquals(CombatState.SUPERFICIE, director.state(),
            "al empezar una pelea no se puede tardar un segundo en reaccionar");
    }

    @Test
    void withoutWebsTheOtherTwoStillGoUpAndTheMissingOneIsNamed() {
        Map<Resource, Integer> noWebs = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, false, false, false, 2, noWebs);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(enables(plan, ManagedModules.AUTO_WEB));
        assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.AUTO_WEB)));
    }

    @Test
    void notEnoughObsidianForATrapSkipsItEvenWithSomeObsidian() {
        Map<Resource, Integer> little = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 2, Resource.WEBS, 5);
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, false, false, false, 2, little);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withNothingAtAllItReportsOutOfResources() {
        CombatSnapshot broke = new CombatSnapshot(true, 3.0, false, false, false, false, 2, Map.of());
        Plan plan = settle(new CombatDirector(), broke);

        assertEquals(CombatState.SIN_RECURSOS, plan.state());
        assertTrue(plan.enable().isEmpty());
        assertFalse(plan.skipped().isEmpty(), "tiene que decir qué le faltó");
    }

    @Test
    void outOfResourcesIsHowItReportsNotWhereItLives() {
        CombatSnapshot broke = new CombatSnapshot(true, 3.0, false, false, false, false, 2, Map.of());
        CombatDirector director = new CombatDirector();
        settle(director, broke);

        assertEquals(CombatState.SUPERFICIE, director.state(), "la fase física sigue siendo SUPERFICIE");
    }

    @Test
    void withoutTotemsTheCrystalAuraIsRefusedButTheRestGoesUp() {
        CombatSnapshot noTotems = new CombatSnapshot(true, 3.0, false, false, false, false, 0, FULL);
        Plan plan = settle(new CombatDirector(), noTotems);

        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA), "sin tótems los cristales te matan a ti");
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.CRYSTAL_AURA)));
    }

    @Test
    void withoutTotemsAndOnlyCrystalsItIsOutOfResources() {
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, false, false, false, false, 0,
            Map.of(Resource.CRYSTALS, 12));
        assertEquals(CombatState.SIN_RECURSOS, settle(new CombatDirector(), snapshot).state());
    }

    @Test
    void resetForgetsTheStateAndTheCounters() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());
        director.reset();

        assertEquals(CombatState.SIN_COMBATE, director.state());
        assertEquals(0, director.ticksInState());
    }
}
