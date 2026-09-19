package com.xploits.elytra.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraPolicyTest {
    private static final int SWAP_BELOW = 10;
    private static final int MIN_SPARE = 50;

    private static ElytraPolicy.Result decide(Integer worn, List<ElytraCandidate> spares, long now) {
        return new ElytraPolicy().decide(worn, spares, SWAP_BELOW, MIN_SPARE, now);
    }

    @Test
    void withoutAnElytraOnThereIsNothingToDecide() {
        assertEquals(Decision.NOT_WEARING, decide(null, List.of(new ElytraCandidate(3, 90)), 1000L).decision());
    }

    @Test
    void anElytraAboveTheThresholdIsLeftAloneEvenWithBetterSparesAround() {
        assertEquals(Decision.OK, decide(40, List.of(new ElytraCandidate(3, 100)), 1000L).decision());
    }

    @Test
    void belowTheThresholdWithOneValidSpareSwapsToIt() {
        ElytraPolicy.Result result = decide(8, List.of(new ElytraCandidate(3, 90)), 1000L);
        assertEquals(Decision.SWAP, result.decision());
        assertEquals(3, result.slot());
    }

    @Test
    void amongSeveralValidSparesItPicksTheWorstOneNotTheBest() {
        ElytraPolicy.Result result = decide(8, List.of(
            new ElytraCandidate(3, 100),
            new ElytraCandidate(7, 55),
            new ElytraCandidate(11, 80)), 1000L);
        assertEquals(Decision.SWAP, result.decision());
        assertEquals(7, result.slot());
    }

    @Test
    void onATieItPicksTheLowestSlot() {
        ElytraPolicy.Result result = decide(8, List.of(
            new ElytraCandidate(11, 60),
            new ElytraCandidate(4, 60)), 1000L);
        assertEquals(4, result.slot());
    }

    @Test
    void noSpareReachesTheMinimum() {
        assertEquals(Decision.NO_SPARE, decide(8, List.of(new ElytraCandidate(3, 49)), 1000L).decision());
    }

    @Test
    void anEmptyInventoryIsNoSpareNotACrash() {
        assertEquals(Decision.NO_SPARE, decide(8, List.of(), 1000L).decision());
    }

    @Test
    void aSpareThatIsNotStrictlyBetterThanTheWornOneIsRefused() {
        // min-spare por debajo de swap-below: sin la regla, esto encadenaría cambios
        ElytraPolicy policy = new ElytraPolicy();
        ElytraPolicy.Result result = policy.decide(8, List.of(new ElytraCandidate(3, 8)), 20, 5, 1000L);
        assertEquals(Decision.NO_SPARE, result.decision());
    }

    @Test
    void aLowMinimumStillSwapsWhenTheSpareIsActuallyBetter() {
        ElytraPolicy.Result result = new ElytraPolicy().decide(8, List.of(new ElytraCandidate(3, 9)), 20, 5, 1000L);
        assertEquals(Decision.SWAP, result.decision());
        assertEquals(3, result.slot());
    }

    @Test
    void aLowMinimumCanChainSeveralSwapsButNeverLoops() {
        // umbral 20, mínimo 5, puesta al 8 % y repuestos al 10 % y al 15 %: encadena 8→10 y luego
        // 10→15 antes de parar. Lo que garantiza la regla de "estrictamente mejor" no es que no
        // encadene, sino que no hay bucle infinito, porque el porcentaje solo sube.
        ElytraPolicy policy = new ElytraPolicy();
        List<ElytraCandidate> spares = List.of(new ElytraCandidate(3, 10), new ElytraCandidate(5, 15));

        ElytraPolicy.Result first = policy.decide(8, spares, 20, 5, 0L);
        assertEquals(Decision.SWAP, first.decision());
        assertEquals(3, first.slot());

        ElytraPolicy.Result second = policy.decide(10, spares, 20, 5, ElytraPolicy.SWAP_COOLDOWN_MS);
        assertEquals(Decision.SWAP, second.decision());
        assertEquals(5, second.slot());

        // Al 15 % ya no queda ningún repuesto estrictamente mejor: aquí para.
        ElytraPolicy.Result third = policy.decide(15, spares, 20, 5, 2 * ElytraPolicy.SWAP_COOLDOWN_MS);
        assertEquals(Decision.NO_SPARE, third.decision());
    }

    @Test
    void itDoesNotRepeatTheSwapInsideTheCooldown() {
        ElytraPolicy policy = new ElytraPolicy();
        List<ElytraCandidate> spares = List.of(new ElytraCandidate(3, 90));
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertEquals(Decision.OK, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 1500L).decision());
    }

    @Test
    void afterTheCooldownItDecidesAgain() {
        ElytraPolicy policy = new ElytraPolicy();
        List<ElytraCandidate> spares = List.of(new ElytraCandidate(3, 90));
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 1000L + ElytraPolicy.SWAP_COOLDOWN_MS).decision());
    }

    @Test
    void aClockJumpBackwardsDoesNotBlockTheSwapForever() {
        ElytraPolicy policy = new ElytraPolicy();
        List<ElytraCandidate> spares = List.of(new ElytraCandidate(3, 90));
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 10_000L).decision());
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 500L).decision());
    }

    @Test
    void theNoSpareWarningIsGivenOnceForTheSameElytra() {
        ElytraPolicy policy = new ElytraPolicy();
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));
        assertFalse(policy.shouldWarnNoSpare(8, 1000L));
        assertFalse(policy.shouldWarnNoSpare(6, 1000L));
    }

    // No hay un test "puttingOnABetterElytraRearmsTheWarning" que llame a shouldWarnNoSpare()
    // directamente con un porcentaje mayor: esa secuencia no la puede producir el adaptador, que
    // siempre pasa por decide() antes, y decide() ya es quien limpia el aviso al ver subir el
    // porcentaje (regla en un solo sitio, spec M4). La cobertura real de "ponerse una elytra
    // mejor rearma el aviso" la da gettingABetterElytraWithoutASwapStillRearmsTheWarning, que sí
    // recorre ese camino.

    @Test
    void resetRearmsTheWarning() {
        ElytraPolicy policy = new ElytraPolicy();
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));
        policy.reset();
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));
    }

    @Test
    void percentIsFullWhenUndamagedAndZeroWhenSpent() {
        assertEquals(100, ElytraPolicy.percentOf(0, 432));
        assertEquals(0, ElytraPolicy.percentOf(432, 432));
        assertEquals(50, ElytraPolicy.percentOf(216, 432));
    }

    @Test
    void anItemWithoutDurabilityDoesNotBreakThePercent() {
        assertEquals(100, ElytraPolicy.percentOf(0, 0));
        assertEquals(100, ElytraPolicy.percentOf(5, -1));
    }

    @Test
    void thePercentNeverLeavesItsRange() {
        assertEquals(0, ElytraPolicy.percentOf(500, 432));
        assertEquals(100, ElytraPolicy.percentOf(-5, 432));
    }

    @Test
    void aFreshPolicySwapsOnItsVeryFirstDecision() {
        assertEquals(Decision.SWAP, decide(8, List.of(new ElytraCandidate(3, 90)), 1000L).decision());
    }

    @Test
    void afterAResetItSwapsAgainWithoutWaitingForTheCooldown() {
        ElytraPolicy policy = new ElytraPolicy();
        List<ElytraCandidate> spares = List.of(new ElytraCandidate(3, 90));
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 1000L).decision());
        policy.reset();
        assertEquals(Decision.SWAP, policy.decide(8, spares, SWAP_BELOW, MIN_SPARE, 1100L).decision());
    }

    @Test
    void gettingABetterElytraWithoutASwapStillRearmsTheWarning() {
        // Elytra A al 8 %, sin repuesto: avisa y queda registrado el 8.
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));

        // El jugador se pone una elytra B nueva al 95 %, sin repuestos que cumplan: decide() ve
        // subir el porcentaje aunque la decisión sea OK, y debe rearmar el aviso.
        assertEquals(Decision.OK, policy.decide(95, List.of(), SWAP_BELOW, MIN_SPARE, 2000L).decision());

        // B se desgasta hasta el mismo 8 % sin repuestos: es otra elytra, así que debe avisar otra
        // vez, y eso ocurre bien dentro de la ventana de 5 minutos: el rearme es por la subida
        // vista en decide(), no por tiempo.
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 3000L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 3000L));
    }

    @Test
    void droppingFurtherWithoutRisingFirstDoesNotWarnAgain() {
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));

        assertEquals(Decision.NO_SPARE, policy.decide(5, List.of(), SWAP_BELOW, MIN_SPARE, 2000L).decision());
        assertFalse(policy.shouldWarnNoSpare(5, 2000L));
    }

    @Test
    void dyingAndPuttingOnAWorseElytraRearmsTheWarning() {
        // Vuela al 8 % sin repuesto: avisa y queda registrado el 8.
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));

        // Muere: la pechera se queda vacía. decide() debe olvidar el aviso registrado, aunque la
        // decisión en sí sea NOT_WEARING y no NO_SPARE.
        assertEquals(Decision.NOT_WEARING, policy.decide(null, List.of(), SWAP_BELOW, MIN_SPARE, 1500L).decision());

        // Reaparece y se pone la única elytra que tenía, al 5 %: es peor que la anterior (8 %),
        // así que sin el rearme por pechera vacía nunca avisaría. Debe avisar igualmente.
        assertEquals(Decision.NO_SPARE, policy.decide(5, List.of(), SWAP_BELOW, MIN_SPARE, 2000L).decision());
        assertTrue(policy.shouldWarnNoSpare(5, 2000L));
    }

    @Test
    void afterFiveMinutesTheWarningRearmsEvenWithoutRisingOrEmptying() {
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 0L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 0L));

        assertEquals(Decision.NO_SPARE,
            policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, ElytraPolicy.WARNING_REARM_WINDOW_MS).decision());
        assertTrue(policy.shouldWarnNoSpare(8, ElytraPolicy.WARNING_REARM_WINDOW_MS));
    }

    @Test
    void beforeFiveMinutesWithoutEmptyingTheChestplateDoesNotRearm() {
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 0L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 0L));

        long justBefore = ElytraPolicy.WARNING_REARM_WINDOW_MS - 1;
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, justBefore).decision());
        assertFalse(policy.shouldWarnNoSpare(8, justBefore));
    }

    @Test
    void theThresholdIncludesTheWornPercentageItself() {
        // swapBelow = 10 y la puesta también al 10 %: la frontera es inclusiva, no es OK.
        assertEquals(Decision.SWAP, decide(10, List.of(new ElytraCandidate(3, 90)), 1000L).decision());
    }

    @Test
    void percentOfRoundsDownOnAnInexactDivision() {
        assertEquals(66, ElytraPolicy.percentOf(1, 3));
    }
}
