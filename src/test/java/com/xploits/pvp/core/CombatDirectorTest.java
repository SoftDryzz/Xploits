package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDirectorTest {
    private static final int APPROACH = 6;

    private static final Map<Resource, Integer> FULL = Map.of(
        Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64,
        Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);

    /** Un surround entero: los cuatro vecinos horizontales del objetivo son minables (M1). */
    private static final int SURROUNDED_SIDES = 4;

    /** Distancia a la que se ENTRA en RODEADO: el límite real menos la banda hacia dentro (I1). */
    private static final double CITY_ENTER =
        CombatDirector.AUTO_CITY_BREAK_RANGE - CombatDirector.NEAR_LIMIT_BAND;

    /** Lo mismo para la cota del objetivo, la otra de las dos de auto-city. */
    private static final double CITY_TARGET_ENTER =
        CombatDirector.AUTO_CITY_TARGET_RANGE - CombatDirector.NEAR_LIMIT_BAND;

    /** Enemigo cerca (3,0), a pie, limpio, con todo el equipo encima y sin nada apuntándote. */
    private static CombatSnapshot surface() {
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, FULL)
            .withTargetId("enemigo");
    }

    /**
     * Este tick no hay objetivo, pero tú sigues siendo tú. Es lo que produce un parpadeo real: el
     * adaptador lee tu inventario y tu vida aunque no haya a quién mirar, así que perder el objetivo
     * no es lo mismo que {@link CombatSnapshot#none()}, que además se queda sin tótems.
     */
    private static CombatSnapshot noTarget() {
        return Snapshots.of(false, 0, 0, 0, false, false, false, 2, FULL);
    }

    private static CombatSnapshot with(CombatSnapshot base, boolean surrounded, boolean burrowed,
                                       boolean targetGliding, boolean selfGliding) {
        // cityBlockDistance no es relevante para estos tests (ninguno toca la frontera de
        // AUTO_CITY_BREAK_RANGE): usar la propia targetDistance del snapshot base basta
        // para que "surrounded" clasifique RODEADO cuando corresponde.
        return new CombatSnapshot(base.hasTarget(), base.targetDistance(),
            surrounded ? SURROUNDED_SIDES : 0, base.targetDistance(),
            burrowed, targetGliding, selfGliding, base.selfTotems(), base.resources(),
            base.targetId(), base.hostilesInCrystalRange(), base.selfTotalHealth(),
            base.incomingDamage(), base.selfInHole(), base.selfOnGround(), base.selfYChanged(),
            base.crystalAuraAntiSuicide());
    }

    /**
     * Deja que el director se asiente en la fase que pide el snapshot. Con margen para la holgura
     * más larga de todas (la de salir de PERSECUCION tras aterrizar).
     */
    private static Plan settle(CombatDirector director, CombatSnapshot snapshot) {
        Plan plan = null;
        for (int i = 0; i < CombatDirector.GLIDE_EXIT_HOLD_TICKS + 5; i++) {
            plan = director.tick(snapshot, APPROACH);
        }
        return plan;
    }

    /**
     * Le ensena al director un objetivo que se aleja de verdad -1,5 bloques ganados en la ventana
     * de {@link RetreatWatch}, por encima de su START_GAIN- y lo deja a {@code endDistance}.
     * Devuelve el plan del ultimo tick.
     */
    private static Plan pullingAwayTo(CombatDirector director, CombatSnapshot base, double endDistance) {
        Plan plan = null;
        double start = endDistance - 1.5;
        for (int i = 0; i <= RetreatWatch.WINDOW_TICKS; i++) {
            double distance = start + (endDistance - start) * i / RetreatWatch.WINDOW_TICKS;
            plan = director.tick(base.withTargetDistance(distance), APPROACH);
        }
        return plan;
    }

    private static boolean enables(Plan plan, ManagedModule module) {
        return plan.enable().contains(module);
    }

    private static boolean skips(Plan plan, ManagedModule module) {
        return plan.skipped().stream().anyMatch(s -> s.module().equals(module));
    }

    // --- §4.1: la precedencia ---

    @Test
    void withoutATargetItIsOutOfCombatAndAsksForNothing() {
        Plan plan = new CombatDirector().tick(CombatSnapshot.none(), APPROACH);
        assertEquals(CombatState.SIN_COMBATE, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void aTargetNearAndOnFootIsSurface() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void aTargetBeyondTheClassifyRangeIsNotACombatAtAll() {
        // §9: de 16 a 10. Ningún módulo dirigido pasa de 10, así que la franja 10-16 solo producía
        // fases con nombre y sin módulos.
        CombatSnapshot far = surface().withTargetDistance(CombatDirector.CLASSIFY_TARGET_RANGE + 0.5);
        assertEquals(CombatState.SIN_COMBATE, settle(new CombatDirector(), far).state());
    }

    @Test
    void theClassifyRangeConstantIsFixedAtTen() {
        assertEquals(10.0, CombatDirector.CLASSIFY_TARGET_RANGE, 0.0,
            "§9: ningún módulo dirigido pasa de 10");
    }

    @Test
    void atExactlyTheClassifyRangeThereIsStillACombat() {
        CombatSnapshot atBoundary = surface().withTargetDistance(10.0);
        assertEquals(CombatState.ACERCAMIENTO, settle(new CombatDirector(), atBoundary).state());
    }

    // --- §4.1: PERSECUCION solo la dispara el objetivo, y solo fuera de rango de cristal ---

    @Test
    void aGlidingTargetOutOfCrystalRangeIsAChase() {
        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        Plan plan = settle(new CombatDirector(), flying);
        assertEquals(CombatState.PERSECUCION, plan.state());
        assertTrue(plan.enable().isEmpty(),
            "§4.2: auto-web nunca coloca a velocidad de elytra; encenderlo era fingir que se hacía algo");
    }

    @Test
    void aGlidingTargetWithinCrystalRangeIsAnOrdinaryFight() {
        // §11: el objetivo planeando a 2 bloques es SUPERFICIE, no PERSECUCION. Volando y pegado a
        // ti es una pelea normal, y los cristales le entran igual.
        CombatSnapshot diving = with(surface(), false, false, true, false).withTargetDistance(2.0);
        Plan plan = settle(new CombatDirector(), diving);
        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void atExactlyTheCrystalRangeAGlidingTargetIsStillAnOrdinaryFight() {
        CombatSnapshot atBoundary = with(surface(), false, false, true, false)
            .withTargetDistance(CombatDirector.CRYSTAL_RANGE);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), atBoundary).state(),
            "la comparación es estrictamente mayor que: igual al umbral los cristales aún entran");
    }

    @Test
    void youGlidingChangesNoPhaseAtAll() {
        // §4.1: en este servidor se vuela casi siempre, así que selfGliding() dejaba al director
        // en PERSECUCION -la fase que menos hace- la mayor parte del tiempo. Que vueles tú no dice
        // nada del enemigo.
        for (CombatState expected : new CombatState[]{CombatState.SUPERFICIE, CombatState.RODEADO,
            CombatState.ENTERRADO, CombatState.ACERCAMIENTO}) {
            CombatSnapshot onFoot = switch (expected) {
                case RODEADO -> with(surface(), true, false, false, false);
                case ENTERRADO -> with(surface(), false, true, false, false);
                case ACERCAMIENTO -> surface().withTargetDistance(8.0);
                default -> surface();
            };
            CombatSnapshot flying = with(onFoot, onFoot.targetSurroundSides() >= CombatDirector.SURROUND_MIN_SIDES,
                onFoot.targetBurrowed(), false, true);

            assertEquals(expected, settle(new CombatDirector(), onFoot).state());
            assertEquals(expected, settle(new CombatDirector(), flying).state(),
                "volar tú no debe cambiar " + expected);
        }
    }

    // --- §4.1: ENTERRADO exige rango de yunque y se mide por protección, no por solidez ---

    @Test
    void aBurrowedTargetWithinAnvilRangeIsBurrowed() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false));
        assertEquals(CombatState.ENTERRADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
    }

    @Test
    void aBurrowedTargetTwelveBlocksAwayIsNotBurrowed() {
        // §11: un enterrado a 12 no es una fase, es un obstáculo. AutoAnvil trabaja a 4: lo que
        // toca es acercarse, no apagar el aura y plantarse. A 12 ni siquiera hay combate (§9).
        CombatSnapshot farBurrowed = with(surface(), false, true, false, false).withTargetDistance(12.0);
        Plan plan = settle(new CombatDirector(), farBurrowed);

        assertEquals(CombatState.SIN_COMBATE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_ANVIL));
    }

    @Test
    void aBurrowedTargetJustBeyondAnvilRangeIsNotBurrowedEither() {
        CombatSnapshot justBeyond = with(surface(), false, true, false, false)
            .withTargetDistance(Math.nextUp(CombatDirector.AUTO_ANVIL_TARGET_RANGE));
        Plan plan = settle(new CombatDirector(), justBeyond);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_ANVIL), "auto-anvil no llega: no se enciende");
    }

    @Test
    void justInsideTheAnvilBandItIsBurrowed() {
        // I1: para ENTRAR hay que estar medio bloque por dentro del alcance real de auto-anvil.
        CombatSnapshot inside = with(surface(), false, true, false, false)
            .withTargetDistance(CombatDirector.AUTO_ANVIL_TARGET_RANGE - CombatDirector.NEAR_LIMIT_BAND);
        assertEquals(CombatState.ENTERRADO, settle(new CombatDirector(), inside).state());
    }

    @Test
    void jumpingNextToABurrowedEnemyDoesNotMakeThePhaseOscillate() {
        // I1, la pelea: te plantas a 3,4 de un enterrado y saltas. Subir 1,25 de Y alarga la
        // distancia a 3,62 durante unos seis ticks -mas que los dos de BLOCK_HOLD_TICKS-, y con el
        // umbral desnudo de antes la fase se caia de ENTERRADO y volvia, abortando la secuencia de
        // auto-anvil a media caida. Contra alguien enterrado, saltar es lo normal.
        CombatDirector director = new CombatDirector();
        CombatSnapshot burrowed = with(surface(), false, true, false, false).withTargetDistance(3.4);
        assertEquals(CombatState.ENTERRADO, settle(director, burrowed).state());

        for (int i = 0; i < 6; i++) {
            Plan plan = director.tick(burrowed.withTargetDistance(3.62), APPROACH);
            assertEquals(CombatState.ENTERRADO, plan.state(), "tick " + i + " del salto");
            assertTrue(enables(plan, ManagedModules.AUTO_ANVIL), "tick " + i + ": el yunque sigue");
        }
    }

    @Test
    void aBurrowedEnemyBeyondTheAnvilRangeLeavesThePhase() {
        // La banda es hacia dentro y no hacia fuera: pasado el alcance real de auto-anvil se sale,
        // porque encender algo que no llega es el fallo silencioso que el principio 10 prohibe.
        CombatDirector director = new CombatDirector();
        CombatSnapshot burrowed = with(surface(), false, true, false, false).withTargetDistance(3.4);
        assertEquals(CombatState.ENTERRADO, settle(director, burrowed).state());

        CombatSnapshot away = burrowed.withTargetDistance(Math.nextUp(CombatDirector.AUTO_ANVIL_TARGET_RANGE));
        assertEquals(CombatState.SUPERFICIE, settle(director, away).state());
    }

    @Test
    void theAnvilRangeConstantIsFixedAtFour() {
        assertEquals(4.0, CombatDirector.AUTO_ANVIL_TARGET_RANGE, 0.0,
            "4 es el target-range de fábrica de auto-anvil");
    }

    @Test
    void standingOnASlabIsNotBurrowedButStandingOnObsidianIs() {
        // §11 y §2: blocksMovement() daba por buena una losa inferior (0,833 de lado medio), así que
        // estar de pie sobre una losa, una escalera, un cofre o una trampilla se clasificaba
        // ENTERRADO. El núcleo recibe ya la respuesta a la pregunta correcta -"¿le protege de un
        // cristal?", blast >= 600 y cubo completo-, así que aquí se fija que la fase sale de esa
        // marca y de nada más.
        CombatSnapshot onASlab = with(surface(), false, false, false, false);
        CombatSnapshot onObsidian = with(surface(), false, true, false, false);

        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), onASlab).state());
        assertEquals(CombatState.ENTERRADO, settle(new CombatDirector(), onObsidian).state());
    }

    // --- §4.2.1: RODEADO, con las dos cotas de auto-city intactas ---

    @Test
    void aSurroundedTargetCallsForAutoCityAndCrystalAura() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, false, false, false));
        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    /**
     * Rodeado según Meteor (`getCityBlock() != null`), objetivo siempre cerca (3.0, muy por debajo
     * de {@code approachDistance}), variando solo la distancia REAL al bloque de rodeado -no al
     * objetivo- (spec §4.2.1, corregido: antes de la corrección esta cota se aplicaba, mal, sobre
     * targetDistance).
     */
    private static CombatSnapshot surroundedAt(double cityBlockDistance) {
        return Snapshots.of(true, 3.0, SURROUNDED_SIDES, cityBlockDistance, false, false, false, 2, FULL);
    }

    @Test
    void surroundedButBeyondAutoCityRangeIsNotRodeado() {
        // CRÍTICO: getCityBlock() ve hasta 6 bloques, pero auto-city se apaga solo -con error en
        // el chat- más allá de su break-range (4.5 de fábrica). En esa franja intermedia el
        // director no debe pedir RODEADO: encendería y apagaría auto-city sin parar (spec §4.2.1).
        CombatSnapshot beyond = surroundedAt(CombatDirector.AUTO_CITY_BREAK_RANGE + 1.0);
        Plan plan = settle(new CombatDirector(), beyond);

        assertEquals(CombatState.SUPERFICIE, plan.state(), "dentro de approach-distance, cae a SUPERFICIE");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void surroundedAndWithinAutoCityRangeIsRodeado() {
        CombatSnapshot within = surroundedAt(CombatDirector.AUTO_CITY_BREAK_RANGE - 1.0);
        Plan plan = settle(new CombatDirector(), within);

        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void atExactlyTheAutoCityRangeYouDoNotEnterRodeadoButYouDoStayInIt() {
        // I1: la banda de estas cotas es hacia dentro. Justo en el break-range real no se ENTRA
        // -hace falta medio bloque mas cerca-, pero si ya estabas dentro no se SALE: ahi esta la
        // holgura, y auto-city nunca se queda fuera de su alcance, que es donde se apaga solo.
        CombatSnapshot atBoundary = surroundedAt(CombatDirector.AUTO_CITY_BREAK_RANGE);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), atBoundary).state());

        CombatDirector director = new CombatDirector();
        assertEquals(CombatState.RODEADO, settle(director, surroundedAt(CITY_ENTER)).state());
        assertEquals(CombatState.RODEADO, settle(director, atBoundary).state(),
            "ya dentro, el limite real todavia aguanta");
    }

    @Test
    void justBeyondTheAutoCityRangeItIsNoLongerRodeado() {
        CombatSnapshot justBeyond = surroundedAt(Math.nextUp(CombatDirector.AUTO_CITY_BREAK_RANGE));
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), justBeyond).state(),
            "un paso por encima del umbral ya no es RODEADO");
    }

    @Test
    void aTargetCloseWithACityBlockOnTheFarSideIsNotRodeado() {
        // Contraejemplo real del CRÍTICO: jugador en (0.5, 0, 0.5), objetivo en (4.5, 0, 0.5)
        // -distancia al objetivo 4.0, "cerca" bajo el criterio antiguo-, bloque de rodeado en
        // (5, 0, 0) -al lado contrario del jugador respecto al objetivo-, distancia al cuadrado
        // 20.5 > 4.5² = 20.25. Con el criterio antiguo (proxy: distancia al objetivo) esto se
        // declaraba RODEADO y auto-city se apagaba solo, con error, cada tick.
        double realBlockDistance = Math.sqrt(20.5);
        CombatSnapshot snapshot = Snapshots.of(true, 4.0, SURROUNDED_SIDES, realBlockDistance, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SUPERFICIE, plan.state(),
            "objetivo cerca pero el bloque real de rodeado está fuera del alcance de auto-city");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void theAutoCityRangeConstantIsFixedAtFourPointFive() {
        // Fija el valor, no solo su existencia: sin esto, cambiar la constante a 2, 3 o 4 deja
        // el resto de tests en verde porque todos se expresan en función de ella misma.
        assertEquals(4.5, CombatDirector.AUTO_CITY_BREAK_RANGE, 0.0,
            "4.5 es el break-range de fábrica de auto-city en Meteor");
    }

    @Test
    void aCityBlockAtTheLiteralFourIsRodeado() {
        // Con literales, no con las constantes: si alguien mueve AUTO_CITY_BREAK_RANGE o la banda,
        // este test lo detecta -al contrario que surroundedAt(), que se moveria con ellas-. 4,0 es
        // el 4,5 de fabrica menos el medio bloque de banda.
        CombatSnapshot atLiteralBoundary = Snapshots.of(true, 3.0, SURROUNDED_SIDES, 4.0, false, false, false, 2, FULL);
        assertEquals(CombatState.RODEADO, settle(new CombatDirector(), atLiteralBoundary).state());
    }

    @Test
    void aCityBlockJustBeyondTheLiteralFourIsNotRodeado() {
        CombatSnapshot justBeyond = Snapshots.of(true, 3.0, SURROUNDED_SIDES, 4.01, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), justBeyond).state());
    }

    /**
     * CRÍTICO (tercera corrección): AUTO_CITY_BREAK_RANGE por sí sola no basta. AutoCity.onTick()
     * comprueba primero TargetUtils.isBadTarget(target, targetRange) -distancia al OBJETIVO, no al
     * bloque- y se apaga solo si falla, antes de mirar el bloque en absoluto.
     */
    @Test
    void targetBeyondAutoCityTargetRangeIsNotRodeadoEvenWithTheBlockClose() {
        CombatSnapshot snapshot = Snapshots.of(true, CombatDirector.AUTO_CITY_TARGET_RANGE + 0.5,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SUPERFICIE, plan.state(),
            "bloque al alcance pero el objetivo real está fuera del target-range de auto-city");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void targetWithinAutoCityTargetRangeAndBlockCloseIsRodeado() {
        CombatSnapshot snapshot = Snapshots.of(true, CombatDirector.AUTO_CITY_TARGET_RANGE - 0.5,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void atExactlyTheAutoCityTargetRangeYouDoNotEnterRodeadoButYouDoStayInIt() {
        // La segunda cota de RODEADO tiene la misma banda hacia dentro que la primera (I1).
        CombatSnapshot atBoundary = Snapshots.of(true, CombatDirector.AUTO_CITY_TARGET_RANGE,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), atBoundary).state());

        CombatDirector director = new CombatDirector();
        CombatSnapshot inside = Snapshots.of(true, CITY_TARGET_ENTER,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.RODEADO, settle(director, inside).state());
        assertEquals(CombatState.RODEADO, settle(director, atBoundary).state(),
            "ya dentro, el limite real todavia aguanta");
    }

    @Test
    void justBeyondTheAutoCityTargetRangeItIsNoLongerRodeado() {
        CombatSnapshot justBeyond = Snapshots.of(true, Math.nextUp(CombatDirector.AUTO_CITY_TARGET_RANGE),
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), justBeyond).state(),
            "un paso por encima del umbral ya no es RODEADO");
    }

    @Test
    void theAutoCityTargetRangeConstantIsFixedAtFivePointFive() {
        assertEquals(5.5, CombatDirector.AUTO_CITY_TARGET_RANGE, 0.0,
            "5.5 es el target-range de fábrica de auto-city en Meteor");
    }

    @Test
    void aTargetAtTheLiteralFiveIsRodeado() {
        // 5,0 es el target-range de fabrica (5,5) menos el medio bloque de banda.
        CombatSnapshot atLiteralBoundary = Snapshots.of(true, 5.0, SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.RODEADO, settle(new CombatDirector(), atLiteralBoundary).state());
    }

    @Test
    void aTargetJustBeyondTheLiteralFiveIsNotRodeado() {
        CombatSnapshot justBeyond = Snapshots.of(true, 5.01, SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), justBeyond).state());
    }

    @Test
    void burrowedBeatsSurroundedWhenBothAreTrue() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, true, false, false));
        assertEquals(CombatState.ENTERRADO, plan.state());
    }

    @Test
    void chaseBeatsBurrowedWhenBothAreTrueAndHeIsFar() {
        CombatSnapshot snapshot = with(surface(), false, true, true, false).withTargetDistance(8.0);
        assertEquals(CombatState.PERSECUCION, settle(new CombatDirector(), snapshot).state());
    }

    // --- §4.2: el reparto por fase ---

    @Test
    void surfaceAsksForTheAuraAndTheTrapWhenHeIsWithinTrapRange() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "a 3,0 auto-trap llega");
        assertFalse(enables(plan, ManagedModules.AUTO_WEB),
            "§4.3: pegado y sin irse, la telaraña le roba al aura su mejor posición de cristal");
    }

    @Test
    void surfaceDoesNotAskForTheTrapBeyondItsRange() {
        // §10: ningún módulo se enciende fuera de su alcance real. AutoTrap trabaja a 3.
        CombatSnapshot snapshot = surface().withTargetDistance(Math.nextUp(CombatDirector.AUTO_TRAP_TARGET_RANGE));
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertFalse(skips(plan, ManagedModules.AUTO_TRAP),
            "no llega: no es una omisión por recursos, es que no se pide");
    }

    @Test
    void theTrapRangeConstantIsFixedAtThree() {
        assertEquals(3.0, CombatDirector.AUTO_TRAP_TARGET_RANGE, 0.0,
            "3 es el target-range de fábrica de auto-trap");
    }

    @Test
    void approachAsksForNothingAtAll() {
        // §4.2: ACERCAMIENTO queda como etiqueta de informe. Entre 6 y 16 bloques no hay nada útil
        // que encender, y surround -lo único que pedía antes- te encerraba en obsidiana mientras
        // corrías y se gastaba la que auto-trap iba a necesitar.
        Plan plan = settle(new CombatDirector(), surface().withTargetDistance(8.0));

        assertEquals(CombatState.ACERCAMIENTO, plan.state());
        assertTrue(plan.enable().isEmpty());
        assertFalse(enables(plan, ManagedModules.SURROUND));
    }

    @Test
    void burrowedAsksForTheAnvilAndTheTrapWhenTheTrapReaches() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false));
        assertEquals(CombatState.ENTERRADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "el trap es para cuando salga del burrow");
        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA),
            "sin otros hostiles a rango de cristal, contra un enterrado el aura no sirve");
    }

    @Test
    void burrowedDoesNotAskForTheTrapBeyondItsRange() {
        // ENTERRADO llega a 4 y auto-trap a 3: entre medias no llega.
        CombatSnapshot snapshot = with(surface(), false, true, false, false).withTargetDistance(3.5);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.ENTERRADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
    }

    // --- §4.3: auto-web no le roba el sitio al aura ---

    @Test
    void theWebDoesNotGoUpJustBecauseHeIsFourBlocksAway() {
        // I2: la mitad "o esta a mas de 3 bloques" de la puerta se fue entera, y no por una banda.
        // Su razon era que a mas de 3 la casilla que telarana ya no seria la del proximo cristal, y
        // eso es falso: el place-range del aura es 4,5, asi que en toda la franja que queda bajo la
        // cota nueva el aura sigue queriendo esa casilla. Quieto a 4 bloques no hay telarana.
        Plan plan = settle(new CombatDirector(), surface().withTargetDistance(4.0));
        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_WEB));
    }

    @Test
    void aTargetDancingAroundThreeBlocksNoLongerFlipsTheWeb() {
        // I2, la medida: con el objetivo bailando en 3,0 se contaron 39 cambios de estado en 40
        // ticks, porque el umbral desnudo cortocircuitaba con un || la banda muerta que RetreatWatch
        // si cuida. Sin ese umbral no hay nada que cruzar.
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(3.0));
        for (int i = 0; i < 40; i++) {
            Plan plan = director.tick(surface().withTargetDistance(i % 2 == 0 ? 2.9 : 3.1), APPROACH);
            assertFalse(enables(plan, ManagedModules.AUTO_WEB), "tick " + i + ": bailar no es irse");
        }
    }

    @Test
    void theWebDoesNotGoUpBeyondItsPlaceRangeEvenIfHeIsPullingAway() {
        // I5: SUPERFICIE llega a 7 y el place-range de AutoWeb es 4. Entre 4 y 7 el director lo
        // encendia y el modulo no colocaba nada: el fallo silencioso que el principio 10 prohibe, y
        // el unico de los dirigidos que se habia quedado sin cota superior.
        CombatDirector director = new CombatDirector();
        Plan plan = pullingAwayTo(director, surface(), 5.0);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(director.targetRetreating(), "precondicion: el director lo ve alejarse");
        assertFalse(enables(plan, ManagedModules.AUTO_WEB),
            "se esta yendo, pero a 5 bloques la telarana no llega");
    }

    @Test
    void theWebGoesUpWithinItsPlaceRangeWhenHeIsPullingAway() {
        CombatDirector director = new CombatDirector();
        Plan plan = pullingAwayTo(director, surface(), CombatDirector.AUTO_WEB_PLACE_RANGE);

        assertTrue(director.targetRetreating(), "precondicion: el director lo ve alejarse");
        assertTrue(enables(plan, ManagedModules.AUTO_WEB),
            "justo en el place-range todavia coloca: la comparacion es menor-o-igual-que");
    }

    @Test
    void theWebGoesUpPointBlankOnlyIfHeIsSustainedlyPullingAway() {
        CombatDirector director = new CombatDirector();
        // Se asienta pegado y quieto: la telaraña no debe encenderse.
        Plan still = settle(director, surface().withTargetDistance(2.0));
        assertFalse(enables(still, ManagedModules.AUTO_WEB));

        // Ahora gana terreno poco a poco sin llegar a salir del rango del trap: 0,1 bloques por
        // tick es 1 bloque en la ventana de RetreatWatch, justo lo que declara "se aleja".
        Plan plan = null;
        double distance = 2.0;
        for (int i = 0; i < RetreatWatch.WINDOW_TICKS + 1; i++) {
            distance += 0.1;
            plan = director.tick(surface().withTargetDistance(Math.min(distance, 3.0)), APPROACH);
        }

        assertTrue(director.targetRetreating(), "precondición: el director lo ve alejarse");
        assertTrue(enables(plan, ManagedModules.AUTO_WEB),
            "la telaraña sirve para impedir que se vaya, y se está yendo");
    }

    @Test
    void theWebDoesNotGoUpPointBlankJustBecauseHeMoves() {
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(2.0));

        // Orbitar alrededor del enemigo cambia la distancia en cada tick, pero no gana terreno.
        Plan plan = null;
        for (int i = 0; i < 40; i++) {
            plan = director.tick(surface().withTargetDistance(i % 2 == 0 ? 1.6 : 2.4), APPROACH);
            assertFalse(enables(plan, ManagedModules.AUTO_WEB), "tick " + i + ": moverse no es irse");
        }
        assertFalse(director.targetRetreating());
    }

    // --- §4.4: el aura no se apaga por la fase si hay a quién cristalear ---

    @Test
    void withAnotherHostileInCrystalRangeTheAuraIsWantedAgainstTheBurrowedOne() {
        // §11: el cebo obvio -uno se entierra, el otro te cristalea, y el director te apaga el aura
        // contra el segundo-. crystal-aura pelea contra todos a la vez.
        CombatSnapshot snapshot = with(surface(), false, true, false, false).withHostiles(1);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.ENTERRADO, plan.state(), "la fase sigue siendo la del objetivo");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL), "y lo de la fase sigue estando");
    }

    @Test
    void withAHostileInCrystalRangeTheAuraIsWantedEvenInAChase() {
        CombatSnapshot snapshot = with(surface(), false, false, true, false)
            .withTargetDistance(8.0).withHostiles(2);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.PERSECUCION, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withoutUnprotectedHostilesThePhaseDecidesTheAuraAsBefore() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false).withHostiles(0));
        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void theOneWhoBurrowsHimselfInFrontOfYouStillKeepsTheAuraOn() {
        // C1, la pelea entera: estás a 3,5 de un tipo que va perdiendo y se entierra -el movimiento
        // estándar del servidor-. La fase pasa a ENTERRADO, él te sigue poniendo cristales desde
        // dentro del burrow, y con la regla anterior -que solo contaba a los hostiles SIN PROTEGER-
        // la cuenta era cero y el ledger te apagaba el autobreak contra el único que podía matarte.
        // Ahora se cuenta él mismo: enterrado o no, sus cristales te entran igual y romper no te
        // cuesta ninguno.
        CombatSnapshot snapshot = with(surface(), false, true, false, false)
            .withTargetDistance(3.5)
            .withHostiles(1);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.ENTERRADO, plan.state(), "la fase sigue siendo la del objetivo");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA),
            "C1: apagarla cuesta la pelea; dejarla encendida de más cuesta unos cristales");
    }

    @Test
    void aSurroundedTargetAlsoCountsForTheAura() {
        // La otra mitad de C1: "protegido" incluía al rodeado, y el rodeado te cristalea igual.
        CombatSnapshot snapshot = with(surface(), true, false, false, false).withHostiles(1);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void theCrystalRangeConstantIsFixedAtFourPointFive() {
        // M2: 4,5 es el place-range y el break-range de fábrica de CrystalAura, verificados en sus
        // fuentes. El 10 es su target-range, que solo dice a quién mira.
        assertEquals(4.5, CombatDirector.CRYSTAL_RANGE, 0.0,
            "M2: place-range y break-range de CrystalAura, no el 5,5 que se suponía");
    }

    @Test
    void withTheShortestApproachTheAuraStillCoversUpToCrystalRange() {
        // M3: con approach-distance en 2, la fase deja de pedir el aura entre 3 y el rango de
        // cristal -ahí la fase es ACERCAMIENTO, que no enciende nada-. El hueco lo cierra C1: a esa
        // distancia el objetivo es un hostil a rango de cristal y la cuenta lo ve.
        CombatSnapshot snapshot = surface().withTargetDistance(4.0).withHostiles(1);
        CombatDirector director = new CombatDirector();
        Plan plan = null;
        for (int i = 0; i < 20; i++) plan = director.tick(snapshot, 2);

        assertEquals(CombatState.ACERCAMIENTO, plan.state(), "con approach 2, a 4 bloques es lejos");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA),
            "M3: el suelo del ajuste no deja un hueco sin aura dentro del rango de cristal");
    }

    // --- M1: un muro de obsidiana no es un surround ---

    @Test
    void anEnemyStandingNextToAWallIsNotSurrounded() {
        // getCityBlock() solo dice "hay UN bloque minable pegado a él", así que un enemigo de pie
        // junto al muro de obsidiana de cualquier base -o junto a la obsidiana que tu propio
        // auto-trap acaba de colocar- clasificaba RODEADO y el director se ponía a minar la pared.
        CombatSnapshot nextToAWall = Snapshots.of(true, 3.0, 1, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), nextToAWall);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_CITY), "no hay pared que minar");
    }

    @Test
    void anEnemyInACornerWithTwoSidesIsNotSurroundedEither() {
        CombatSnapshot inACorner = Snapshots.of(true, 3.0, 2, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), inACorner).state());
    }

    @Test
    void aSurroundWithOneSideAlreadyBrokenIsStillASurround() {
        // Tres de cuatro: es el caso más común de todos, seguir minando el que ya habías empezado.
        CombatSnapshot threeSides = Snapshots.of(true, 3.0, 3, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), threeSides);

        assertEquals(CombatState.RODEADO, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    // --- I3: tres colocadores, una sola pila de obsidiana ---

    /** En el agujero, amenazado, con el enemigo encima y la obsidiana que se diga. */
    private static CombatSnapshot inAHoleWithObsidian(int obsidian) {
        Map<Resource, Integer> resources = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, obsidian,
            Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources)
            .withTargetId("enemigo")
            .withDefense(10.0, 0.0, true, true);
    }

    @Test
    void withEightObsidianTheThreePlacersDoNotAllGetApproved() {
        // I3, la situación: en un agujero, amenazado y con el enemigo encima suben a la vez
        // auto-trap (mínimo 8), surround (4) y hole-filler (1). Con ocho obsidianas piden trece
        // entre los tres, los tres hacen swap a la misma pila el mismo tick y ninguno completa su
        // trabajo. El reparto es defensivo antes que ofensivo y barato antes que caro.
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(8));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER), "el más barato y el más defensivo");
        assertTrue(enables(plan, ManagedModules.SURROUND), "1 + 4 caben en 8");
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "quedan 3 y necesita 8");
    }

    @Test
    void theModuleLeftOutOfTheObsidianIsSaidOutLoud() {
        // Lo que no vale es aprobar más de lo que hay y callárselo: antes skipped salía vacío.
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(8));

        assertTrue(skips(plan, ManagedModules.AUTO_TRAP));
        Msg reason = plan.skipped().stream()
            .filter(skipped -> skipped.module().equals(ManagedModules.AUTO_TRAP))
            .map(Skipped::reason).findFirst().orElse(null);
        assertEquals(Msg.of(PvpText.SHORTAGE_SHARED, "have", 8,
                "others", Msg.of(PvpText.JOIN_AND, "first", "hole-filler", "second", "surround"),
                "left", 3, "minimum", 8), reason,
            "el motivo tiene que nombrar a quien se llevó la obsidiana");
    }

    @Test
    void withEnoughObsidianForTheThreeOfThemTheThreeGoUp() {
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(13));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER));
        assertTrue(enables(plan, ManagedModules.SURROUND));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "13 son exactamente 1 + 4 + 8");
    }

    @Test
    void withFourObsidianOnlyTheCheapestDefensiveOnesGetTheirShare() {
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(4));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER), "tapar el hueco cuesta una");
        assertFalse(enables(plan, ManagedModules.SURROUND), "quedan 3 y necesita 4");
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
    }

    // --- I4: la memoria de recursos del eje defensivo no se congela en SIN_COMBATE ---

    @Test
    void holeFillerDoesNotFlickerOutOfCombatWhileTheObsidianComesAndGoes() {
        // I4: previouslyEnabled se congelaba entera mientras la fase fuera SIN_COMBATE, para no
        // borrar la memoria de recursos en un parpadeo del objetivo. Pero el eje defensivo SÍ
        // decide en SIN_COMBATE, y con su memoria congelada su ventana de gracia no llegaba a
        // arrancar nunca: amenazado y con la obsidiana yendo y viniendo -colocas una, recoges
        // otra-, hole-filler entraba y salía una vez por cada cruce del mínimo, con su línea de
        // chat cada vez.
        CombatDirector director = new CombatDirector();

        int changes = 0;
        Boolean previous = null;
        for (int tick = 0; tick < 80; tick++) {
            int obsidian = tick % 10 < 3 ? 1 : 0;
            CombatSnapshot alone = Snapshots.of(false, 0, 0, 0, false, false, false, 2,
                    Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, obsidian))
                .withDefense(10.0, 0.0, true, true);
            boolean up = enables(director.tick(alone, APPROACH), ManagedModules.HOLE_FILLER);

            assertEquals(CombatState.SIN_COMBATE, director.state(), "precondición: no hay objetivo");
            if (previous != null && up != previous) changes++;
            previous = up;
        }

        assertEquals(0, changes,
            "la ventana de gracia de recursos tiene que valer también para el eje defensivo: "
                + "ninguno de los huecos de obsidiana llega a los "
                + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks");
        assertTrue(previous, "y al final sigue encendido");
    }

    // --- §5: la postura defensiva ---

    @Test
    void aThreatenedPostureAddsItsModulesOnTopOfThePhase() {
        CombatSnapshot snapshot = surface().withDefense(14.0, 6.0, false, true);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SUPERFICIE, plan.state(), "la fase ofensiva no cambia");
        assertEquals(CombatPosture.AMENAZADO, plan.posture());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA), "los dos ejes se suman, no se eligen");
        assertTrue(enables(plan, ManagedModules.HOLE_FILLER));
        assertTrue(enables(plan, ManagedModules.ANTI_ANVIL));
        assertTrue(enables(plan, ManagedModules.ANTI_BED));
        assertTrue(enables(plan, ManagedModules.ANTI_ANCHOR));
    }

    @Test
    void aCalmPostureAddsNothing() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatPosture.TRANQUILO, plan.posture());
        assertFalse(enables(plan, ManagedModules.HOLE_FILLER));
        assertFalse(enables(plan, ManagedModules.SURROUND));
    }

    @Test
    void surroundOnlyGoesUpThreatenedInAHoleAndOnTheGround() {
        CombatSnapshot inAHole = surface().withDefense(10.0, 0.0, true, true);
        assertTrue(enables(settle(new CombatDirector(), inAHole), ManagedModules.SURROUND));

        CombatSnapshot inTheAir = surface().withDefense(10.0, 0.0, true, false);
        assertFalse(enables(settle(new CombatDirector(), inTheAir), ManagedModules.SURROUND),
            "toggle-on-y-change y centerPlayer(): encenderlo sin pisar suelo se apaga solo en bucle");

        CombatSnapshot outOfTheHole = surface().withDefense(10.0, 0.0, false, true);
        assertFalse(enables(settle(new CombatDirector(), outOfTheHole), ManagedModules.SURROUND),
            "es un módulo defensivo de agujero y ese es su único sitio");
    }

    // --- §6: la histéresis que de verdad protege ---

    @Test
    void losingTheTargetForOneTickDoesNotEndTheFight() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = director.tick(noTarget(), APPROACH);

        assertEquals(CombatState.SUPERFICIE, plan.state(),
            "no hay nadie este tick y se acabó la pelea no son lo mismo");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA),
            "durante la gracia se sigue decidiendo con lo último que se vio de él");
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP));
    }

    @Test
    void losingTheTargetForTheWholeGraceDoesEndTheFight() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = null;
        for (int i = 0; i < CombatDirector.TARGET_GRACE_TICKS - 1; i++) {
            plan = director.tick(noTarget(), APPROACH);
        }
        assertEquals(CombatState.SUPERFICIE, plan.state(), "todavía dentro de la gracia");

        plan = director.tick(noTarget(), APPROACH);
        assertEquals(CombatState.SIN_COMBATE, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void theTargetGraceRestartsWhenHeComesBack() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        // Parpadea una y otra vez sin llegar a faltar la gracia entera: nunca cae a SIN_COMBATE.
        for (int i = 0; i < 100; i++) {
            director.tick(i % 5 == 0 ? surface() : noTarget(), APPROACH);
            assertEquals(CombatState.SUPERFICIE, director.state(), "tick " + i);
        }
    }

    @Test
    void aTargetLeavingTheClassifyRangeAlsoGetsTheGrace() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = director.tick(surface().withTargetDistance(30.0), APPROACH);
        assertEquals(CombatState.SUPERFICIE, plan.state(), "salir de rango es perder el objetivo, con su gracia");

        for (int i = 0; i < CombatDirector.TARGET_GRACE_TICKS; i++) {
            plan = director.tick(surface().withTargetDistance(30.0), APPROACH);
        }
        assertEquals(CombatState.SIN_COMBATE, plan.state());
    }

    @Test
    void theApproachBandDoesNotOscillateWithTheTargetSittingAtTheThreshold() {
        // §11: la banda de distancia no oscila con el objetivo justo en approach.
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        for (int i = 0; i < 40; i++) {
            director.tick(surface().withTargetDistance(APPROACH), APPROACH);
            assertEquals(CombatState.SUPERFICIE, director.state(), "tick " + i + ": justo en el umbral no se sale");
        }
    }

    @Test
    void enteringApproachNeedsToCrossTheUpperEdgeOfTheBand() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        settle(director, surface().withTargetDistance(APPROACH + CombatDirector.APPROACH_BAND));
        assertEquals(CombatState.SUPERFICIE, director.state(), "en el borde superior todavía no");

        settle(director, surface().withTargetDistance(APPROACH + CombatDirector.APPROACH_BAND + 0.1));
        assertEquals(CombatState.ACERCAMIENTO, director.state());
    }

    @Test
    void leavingApproachNeedsToCrossTheLowerEdgeOfTheBand() {
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(9.0));
        assertEquals(CombatState.ACERCAMIENTO, director.state());

        settle(director, surface().withTargetDistance(APPROACH));
        assertEquals(CombatState.ACERCAMIENTO, director.state(),
            "dentro de la banda manda la fase en la que ya estabas");

        settle(director, surface().withTargetDistance(APPROACH - CombatDirector.APPROACH_BAND));
        assertEquals(CombatState.SUPERFICIE, director.state());
    }

    @Test
    void theApproachBandIsCrossedWithoutWaitingAnyTicks() {
        // La histéresis de estas dos fases es de distancia, no de tiempo: no debe costar el primer
        // combo esperar a que se cumpla ninguna permanencia.
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(9.0));

        director.tick(surface().withTargetDistance(2.0), APPROACH);
        assertEquals(CombatState.SUPERFICIE, director.state(), "un solo tick basta");
    }

    @Test
    void aBlockSignalNeedsTwoTicksAndNotMore() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        director.tick(burrowed, APPROACH);
        assertEquals(CombatState.SUPERFICIE, director.state(), "un tick de lectura no basta");

        director.tick(burrowed, APPROACH);
        assertEquals(CombatState.ENTERRADO, director.state(), "dos ticks seguidos sí");
    }

    @Test
    void aFlickeringBlockSignalNeverChangesThePhase() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < 100; i++) director.tick(i % 2 == 0 ? burrowed : surface(), APPROACH);

        assertEquals(CombatState.SUPERFICIE, director.state());
    }

    @Test
    void aTakeoffNeedsItsOwnLongerHold() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        for (int i = 0; i < CombatDirector.GLIDE_ENTER_HOLD_TICKS - 1; i++) director.tick(flying, APPROACH);
        assertEquals(CombatState.SUPERFICIE, director.state(), "un despegue tarda un par de ticks en ser de verdad");

        director.tick(flying, APPROACH);
        assertEquals(CombatState.PERSECUCION, director.state());
    }

    @Test
    void landingBouncesDoNotLeaveTheChaseEarly() {
        CombatDirector director = new CombatDirector();
        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        settle(director, flying);
        assertEquals(CombatState.PERSECUCION, director.state());

        // Aterrizar va rozando el suelo: la marca de planeo se apaga y se enciende varias veces.
        CombatSnapshot landed = with(surface(), false, false, false, false).withTargetDistance(8.0);
        for (int i = 0; i < 60; i++) {
            director.tick(i % 5 == 4 ? flying : landed, APPROACH);
            assertEquals(CombatState.PERSECUCION, director.state(), "tick " + i + ": sigue rebotando");
        }

        for (int i = 0; i < CombatDirector.GLIDE_EXIT_HOLD_TICKS; i++) director.tick(landed, APPROACH);
        assertEquals(CombatState.ACERCAMIENTO, director.state(), "diez ticks seguidos a pie sí salen");
    }

    @Test
    void divingIntoCrystalRangeLeavesTheChaseWithoutWaitingForTheLandingHold() {
        CombatDirector director = new CombatDirector();
        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        settle(director, flying);
        assertEquals(CombatState.PERSECUCION, director.state());

        // Sigue planeando, pero se te ha echado encima: eso es una señal de posición, no un rebote.
        CombatSnapshot diving = with(surface(), false, false, true, false).withTargetDistance(2.0);
        for (int i = 0; i < CombatDirector.BLOCK_HOLD_TICKS; i++) director.tick(diving, APPROACH);

        assertEquals(CombatState.SUPERFICIE, director.state());
    }

    @Test
    void engagingDoesNotWaitAtAll() {
        CombatDirector director = new CombatDirector();
        director.tick(surface(), APPROACH);

        assertEquals(CombatState.SUPERFICIE, director.state(),
            "al empezar una pelea no se puede tardar en reaccionar");
    }

    @Test
    void aFreshPhaseIsAbandonedAsSoonAsTheNewOneHolds() {
        // MIN_DWELL_TICKS desaparece (§6): hacía que el director tardara más en corregir su error
        // que en cometerlo, y nunca debe retrasar una transición que vuelve a encender el aura.
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < CombatDirector.BLOCK_HOLD_TICKS; i++) director.tick(burrowed, APPROACH);
        assertEquals(CombatState.ENTERRADO, director.state());

        CombatSnapshot surrounded = with(surface(), true, false, false, false);
        for (int i = 0; i < CombatDirector.BLOCK_HOLD_TICKS; i++) director.tick(surrounded, APPROACH);
        assertEquals(CombatState.RODEADO, director.state(), "recién entrado o no, la corrección no espera");
    }

    // --- §7: el aura queda fuera del filtro de recursos ---

    @Test
    void withoutCrystalsTheAuraStillGoesUpAndIsReportedAsAWarning() {
        // §11: con 0 cristales, crystal-aura sigue en la lista de encendido. Apagarla te quita el
        // autobreak, que es justo lo que te mantiene vivo cuando no tienes con qué responder.
        Map<Resource, Integer> noCrystals = Map.of(
            Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, noCrystals);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(skips(plan, ManagedModules.CRYSTAL_AURA), "no es una omisión");
        assertTrue(plan.warnings().contains(Msg.of(PvpText.AURA_NO_CRYSTALS)), "se avisa");
        assertEquals(CombatState.SUPERFICIE, plan.state(), "y no se informa SIN_RECURSOS por eso");
    }

    @Test
    void withCrystalsThereIsNoWarning() {
        assertTrue(settle(new CombatDirector(), surface()).warnings().isEmpty());
    }

    @Test
    void withoutTotemsButWithAntiSuicideTheAuraStillGoesUp() {
        // El suelo de tótems era una decisión de vida tomada con un contador de ítems, y Meteor ya
        // la toma con el daño exacto: anti-suicide se niega a colocar o romper un cristal que te
        // mate. Sin tótems es justo cuando más falta hace el autobreak.
        CombatSnapshot noTotems = Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, FULL);
        Plan plan = settle(new CombatDirector(), noTotems);

        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(skips(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "y el resto sigue subiendo");
    }

    @Test
    void withoutTotemsAndWithAntiSuicideOffTheAuraIsRefused() {
        // anti-suicide es solo un valor por defecto: apagado, esa protección no existe y el suelo
        // de tótems vuelve a ser lo único que queda.
        CombatSnapshot noTotems =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, FULL));
        Plan plan = settle(new CombatDirector(), noTotems);

        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(skips(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(plan.skipped().stream().anyMatch(sk -> sk.reason().equals(Msg.of(PvpText.TOTEM_FLOOR))),
            "y el motivo dice por qué, no solo que faltan tótems");
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "y el resto sigue subiendo");
    }

    @Test
    void withTotemsTheAntiSuicideSettingChangesNothing() {
        CombatSnapshot withTotems =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, FULL));
        assertTrue(enables(settle(new CombatDirector(), withTotems), ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withoutTotemsAndOnlyCrystalsItIsOutOfResourcesOnlyWithAntiSuicideOff() {
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 0,
            Map.of(Resource.CRYSTALS, 12));
        assertEquals(CombatState.SUPERFICIE, settle(new CombatDirector(), snapshot).state(),
            "con anti-suicide puesto el aura sube y hay con qué pelear");
        assertEquals(CombatState.SIN_RECURSOS,
            settle(new CombatDirector(), Snapshots.antiSuicideOff(snapshot)).state());
    }

    @Test
    void withoutAPickaxeAutoCitySkipsItInSurrounded() {
        Map<Resource, Integer> noPickaxe = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.ANVILS, 3);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, SURROUNDED_SIDES, 3.0, false, false, false, 2, noPickaxe);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.RODEADO, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(skips(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void withNothingAtAllAndNoTotemsItReportsOutOfResources() {
        // Sin nada encima y sin la red de anti-suicide no queda ni el autobreak.
        CombatSnapshot broke =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, Map.of()));
        Plan plan = settle(new CombatDirector(), broke);

        assertEquals(CombatState.SIN_RECURSOS, plan.state());
        assertTrue(plan.enable().isEmpty());
        assertFalse(plan.skipped().isEmpty(), "tiene que decir qué le faltó");
    }

    @Test
    void withNothingAtAllButWithAntiSuicideTheAutobreakIsStillSomethingToFightWith() {
        CombatSnapshot broke = Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, Map.of());
        Plan plan = settle(new CombatDirector(), broke);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(plan.warnings().contains(Msg.of(PvpText.AURA_NO_CRYSTALS)));
    }

    @Test
    void outOfResourcesIsHowItReportsNotWhereItLives() {
        CombatSnapshot broke =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, Map.of()));
        CombatDirector director = new CombatDirector();
        settle(director, broke);

        assertEquals(CombatState.SUPERFICIE, director.state(), "la fase física sigue siendo SUPERFICIE");
    }

    @Test
    void aPhaseThatAsksForNothingIsNotOutOfResources() {
        CombatSnapshot broke = Snapshots.of(true, 8.0, 0, 0, false, false, false, 0, Map.of());
        assertEquals(CombatState.ACERCAMIENTO, settle(new CombatDirector(), broke).state(),
            "que no haya nada que encender no es quedarse sin recursos");
    }

    @Test
    void notEnoughObsidianForATrapSkipsItEvenWithSomeObsidian() {
        Map<Resource, Integer> little = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 2, Resource.WEBS, 5);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, little);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withoutWebsTheWebIsSkippedWhereItWouldHaveGoneUp() {
        Map<Resource, Integer> noWebs = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot snapshot = Snapshots.of(true, 4.0, 0, 0, false, false, false, 2, noWebs)
            .withTargetId("enemigo");

        CombatDirector director = new CombatDirector();
        Plan plan = pullingAwayTo(director, snapshot, 3.5);

        assertEquals(CombatState.SUPERFICIE, plan.state());
        assertTrue(director.targetRetreating(), "precondicion: la puerta de auto-web esta abierta");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(enables(plan, ManagedModules.AUTO_WEB));
        assertTrue(skips(plan, ManagedModules.AUTO_WEB));
    }

    // --- spec §6.2: la histéresis de recursos, que no se toca ---

    /** SUPERFICIE con la obsidiana de auto-trap a un valor concreto, el resto del equipo completo. */
    private static CombatSnapshot withObsidian(int amount) {
        Map<Resource, Integer> resources = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, amount,
            Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources);
    }

    @Test
    void obsidianBelowMinimumBrieflyKeepsAutoTrapEnabledIfItWasOnBefore() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP), "precondición: ya estaba encendido");

        Plan plan = director.tick(withObsidian(2), APPROACH);
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "un tick por debajo del mínimo no lo suelta");
    }

    @Test
    void obsidianBelowMinimumDoesNotEnableAutoTrapIfItWasNeverOn() {
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

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS; i++) {
            plan = director.tick(withObsidian(0), APPROACH);
        }
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP),
            "cumplidos los " + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks por debajo del mínimo, se suelta");
    }

    @Test
    void theResourceReleaseDwellIsStillTwentyTicks() {
        assertEquals(20, CombatDirector.RESOURCE_RELEASE_DWELL_TICKS,
            "§9: verificado como correcto, no se toca");
    }

    @Test
    void aModuleWithAMinimumOfOneAlsoGetsTheReleaseDwellWindow() {
        // Con minimum() == 1, "la mitad" redondeaba al mismo mínimo y no daba ninguna gracia.
        // auto-anvil (mínimo 1) es uno de los módulos a los que esto afectaba: contra un enterrado
        // a 3 bloques se pide, y la fase no depende de nada que se mueva.
        CombatDirector director = new CombatDirector();
        CombatSnapshot burrowed = with(surface(), false, true, false, false).withTargetDistance(3.0);
        Plan settled = settle(director, burrowed);
        assertTrue(enables(settled, ManagedModules.AUTO_ANVIL), "precondición: ya estaba encendido");

        Map<Resource, Integer> noAnvils = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.PICKAXE, 1);
        CombatSnapshot noWebsSnapshot = new CombatSnapshot(true, 3.0, 0, 0, true, false, false, 2,
            noAnvils, "enemigo", 0, CombatSnapshot.FULL_HEALTH, 0, false, false, false, true);

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS - 1; i++) {
            plan = director.tick(noWebsSnapshot, APPROACH);
        }
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL), "todavía dentro de la ventana de gracia");

        plan = director.tick(noWebsSnapshot, APPROACH);
        assertFalse(enables(plan, ManagedModules.AUTO_ANVIL), "cumplida la ventana, se suelta aunque el mínimo sea 1");
    }

    @Test
    void obsidianOscillatingAroundTheMinimumDoesNotFlickerAutoTrap() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        for (int i = 0; i < 20; i++) {
            Plan plan = director.tick(withObsidian(i % 2 == 0 ? 6 : 10), APPROACH);
            assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "tick " + i + ": no debe parpadear");
        }
    }

    @Test
    void aOneTickBlipWithoutATargetDoesNotEraseTheResourceMemory() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        for (int i = 0; i < 5; i++) director.tick(withObsidian(2), APPROACH);
        assertEquals(CombatState.SUPERFICIE, director.state());

        director.tick(CombatSnapshot.none(), APPROACH);

        Plan plan = director.tick(withObsidian(2), APPROACH);
        assertEquals(CombatState.SUPERFICIE, director.state());
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP),
            "la memoria de recursos debía seguir viva tras el blip, no exigir el mínimo completo de golpe");
    }

    // --- reset ---

    @Test
    void resetForgetsTheStateAndTheCounters() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());
        director.reset();

        assertEquals(CombatState.SIN_COMBATE, director.state());
        assertEquals(0, director.ticksInState());
        assertFalse(director.targetRetreating());
    }

    @Test
    void resetForgetsThePreviouslyEnabledModules() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        director.reset();

        Plan plan = settle(director, withObsidian(7));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "reset() olvida qué estaba encendido");
    }

    @Test
    void resetForgetsTheTargetGrace() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());
        director.reset();

        Plan plan = director.tick(CombatSnapshot.none(), APPROACH);
        assertEquals(CombatState.SIN_COMBATE, plan.state(), "sin memoria del objetivo no hay gracia que dar");
        assertTrue(plan.enable().isEmpty());
    }
}
