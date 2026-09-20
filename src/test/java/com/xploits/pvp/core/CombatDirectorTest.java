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
    void notEnoughObsidianForSurroundSkipsItInApproach() {
        CombatSnapshot far = new CombatSnapshot(true, 7.0, false, false, false, false, 2,
            Map.of(Resource.OBSIDIAN, 2));
        CombatDirector director = new CombatDirector();
        Plan plan = settle(director, far);

        assertEquals(CombatState.ACERCAMIENTO, director.state(), "la fase física sigue siendo ACERCAMIENTO");
        assertFalse(enables(plan, ManagedModules.SURROUND));
        assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.SURROUND)));
    }

    @Test
    void justInsideTheApproachDistanceIsSurface() {
        CombatSnapshot near = new CombatSnapshot(true, 5.0, false, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), near).state());
    }

    @Test
    void atExactlyTheApproachDistanceItIsSurfaceNotApproach() {
        CombatSnapshot atBoundary = new CombatSnapshot(true, APPROACH, false, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), atBoundary).state(),
            "la comparación es estrictamente mayor que: igual al umbral sigue siendo SUPERFICIE");
    }

    @Test
    void aSurroundedTargetCallsForAutoCityAndCrystalAura() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, false, false, false));
        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    /** Rodeado según Meteor (`getCityBlock() != null`) pero fuera del alcance real de auto-city. */
    private static CombatSnapshot surroundedAt(double targetDistance) {
        return new CombatSnapshot(true, targetDistance, true, false, false, false, 2, FULL);
    }

    @Test
    void surroundedButBeyondAutoCityRangeIsNotRodeado() {
        // CRÍTICO: getCityBlock() ve hasta 6 bloques, pero auto-city se apaga solo -con error en
        // el chat- más allá de su break-range (4.5 de fábrica). En esa franja intermedia el
        // director no debe pedir RODEADO: encendería y apagaría auto-city sin parar (spec §4.2).
        CombatSnapshot beyond = surroundedAt(CombatDirector.AUTO_CITY_MAX_TARGET_DISTANCE + 1.0);
        Plan plan = settle(new CombatDirector(), beyond);

        assertEquals(CombatState.SUPERFICIE, plan.state(), "dentro de approach-distance, cae a SUPERFICIE");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void surroundedAndWithinAutoCityRangeIsRodeado() {
        CombatSnapshot within = surroundedAt(CombatDirector.AUTO_CITY_MAX_TARGET_DISTANCE - 1.0);
        Plan plan = settle(new CombatDirector(), within);

        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void atExactlyTheAutoCityRangeItIsStillRodeado() {
        CombatSnapshot atBoundary = surroundedAt(CombatDirector.AUTO_CITY_MAX_TARGET_DISTANCE);
        assertEquals(CombatState.RODEADO, settle(new CombatDirector(), atBoundary).state(),
            "la comparación es menor-o-igual-que: igual al umbral sigue siendo RODEADO");
    }

    @Test
    void justBeyondTheAutoCityRangeItIsNoLongerRodeado() {
        CombatSnapshot justBeyond = surroundedAt(Math.nextUp(CombatDirector.AUTO_CITY_MAX_TARGET_DISTANCE));
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), justBeyond).state(),
            "un paso por encima del umbral ya no es RODEADO");
    }

    @Test
    void withoutTotemsInSurroundedTheCrystalAuraIsRefusedButAutoCityGoesUp() {
        CombatSnapshot noTotems = new CombatSnapshot(true, 3.0, true, false, false, false, 0, FULL);
        Plan plan = settle(new CombatDirector(), noTotems);

        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA),
            "sin tótems los cristales te matan a ti también en RODEADO");
        assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.CRYSTAL_AURA)));
    }

    @Test
    void withoutAPickaxeAutoCitySkipsItInSurrounded() {
        Map<Resource, Integer> noPickaxe = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.ANVILS, 3);
        CombatSnapshot snapshot = new CombatSnapshot(true, 3.0, true, false, false, false, 2, noPickaxe);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.RODEADO, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(plan.skipped().stream().anyMatch(s -> s.module().equals(ManagedModules.AUTO_CITY)));
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

    /** SUPERFICIE con la obsidiana de auto-trap a un valor concreto, el resto del equipo completo. */
    private static CombatSnapshot withObsidian(int amount) {
        Map<Resource, Integer> resources = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, amount,
            Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        return new CombatSnapshot(true, 3.0, false, false, false, false, 2, resources);
    }

    @Test
    void obsidianBelowMinimumBrieflyKeepsAutoTrapEnabledIfItWasOnBefore() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP), "precondición: ya estaba encendido");

        // Un solo tick por debajo del mínimo (8): la permanencia lo mantiene encendido.
        Plan plan = director.tick(withObsidian(2), APPROACH);
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "un tick por debajo del mínimo no lo suelta");
    }

    @Test
    void obsidianBelowMinimumDoesNotEnableAutoTrapIfItWasNeverOn() {
        // Constante en 7 desde el principio: nunca llegó a encenderse, así que nunca hay historial
        // que le dé permanencia. Sin ella exige el mínimo completo, sin ninguna gracia.
        Plan plan = settle(new CombatDirector(), withObsidian(7));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "sin historial exige el mínimo completo");
    }

    @Test
    void sustainedShortageDoesNotDropAutoTrapBeforeTheReleaseDwellWindow() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS - 1; i++) {
            plan = director.tick(withObsidian(0), APPROACH);
        }
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP),
            "todavía no lleva " + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks seguidos por debajo del mínimo");
    }

    @Test
    void sustainedShortageDropsAutoTrapAfterTheReleaseDwellWindow() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        // M2: nada fijaba hasta ahora que la permanencia SÍ se abandona pasados los 20 ticks.
        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS; i++) {
            plan = director.tick(withObsidian(0), APPROACH);
        }
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP),
            "cumplidos los " + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks por debajo del mínimo, se suelta");
    }

    @Test
    void aModuleWithAMinimumOfOneAlsoGetsTheReleaseDwellWindow() {
        // I2: con minimum() == 1, "la mitad" redondeaba al mismo mínimo y no daba ninguna gracia.
        // auto-web (mínimo 1) es uno de los cuatro módulos a los que esto afectaba.
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, surface());
        assertTrue(enables(settled, ManagedModules.AUTO_WEB), "precondición: ya estaba encendido");

        Map<Resource, Integer> noWebs = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot noWebsSnapshot = new CombatSnapshot(true, 3.0, false, false, false, false, 2, noWebs);

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS - 1; i++) {
            plan = director.tick(noWebsSnapshot, APPROACH);
        }
        assertTrue(enables(plan, ManagedModules.AUTO_WEB), "todavía dentro de la ventana de gracia");

        plan = director.tick(noWebsSnapshot, APPROACH);
        assertFalse(enables(plan, ManagedModules.AUTO_WEB), "cumplida la ventana, se suelta aunque el mínimo sea 1");
    }

    @Test
    void obsidianOscillatingAroundTheMinimumDoesNotFlickerAutoTrap() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        // 6 está por debajo del mínimo (8); 10 está por encima. Cada vez que sube al menos al
        // mínimo, la cuenta de ticks por debajo se reinicia a cero, así que oscilar así nunca
        // acumula los RESOURCE_RELEASE_DWELL_TICKS seguidos que hacen falta para soltarlo.
        for (int i = 0; i < 20; i++) {
            Plan plan = director.tick(withObsidian(i % 2 == 0 ? 6 : 10), APPROACH);
            assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "tick " + i + ": no debe parpadear");
        }
    }

    @Test
    void resetForgetsThePreviouslyEnabledModules() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        director.reset();

        // Si reset() no hubiera olvidado el historial, 7 (por debajo del mínimo) seguiría
        // encendiendo auto-trap por la ventana de gracia aunque el director acabe de arrancar de cero.
        Plan plan = settle(director, withObsidian(7));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "reset() olvida qué estaba encendido");
    }

    @Test
    void aOneTickBlipWithoutATargetDoesNotEraseTheResourceMemory() {
        // I3: previouslyEnabled se sobrescribía con el conjunto vacío al pasar por SIN_COMBATE, que
        // se entra sin esperar. Un objetivo que sale un tick de rango y vuelve no debe borrar la
        // memoria de recursos de toda la pelea.
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        // Ya lleva unos ticks por debajo del mínimo, dentro de la ventana de gracia.
        for (int i = 0; i < 5; i++) director.tick(withObsidian(2), APPROACH);
        assertEquals(CombatState.SUPERFICIE, director.state());

        // El objetivo desaparece un instante: la fase física pasa a SIN_COMBATE sin esperar.
        director.tick(CombatSnapshot.none(), APPROACH);
        assertEquals(CombatState.SIN_COMBATE, director.state());

        // Vuelve a verse y se sostiene lo bastante para reenganchar SUPERFICIE.
        Plan plan = null;
        for (int i = 0; i < CombatDirector.CHANGE_HOLD_TICKS; i++) {
            plan = director.tick(withObsidian(2), APPROACH);
        }
        assertEquals(CombatState.SUPERFICIE, director.state());

        assertTrue(enables(plan, ManagedModules.AUTO_TRAP),
            "la memoria de recursos debía seguir viva tras el blip, no exigir el mínimo completo de golpe");
    }
}
