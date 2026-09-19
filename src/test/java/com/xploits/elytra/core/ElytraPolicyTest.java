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
        assertTrue(policy.shouldWarnNoSpare(8));
        assertFalse(policy.shouldWarnNoSpare(8));
        assertFalse(policy.shouldWarnNoSpare(6));
    }

    @Test
    void puttingOnABetterElytraRearmsTheWarning() {
        ElytraPolicy policy = new ElytraPolicy();
        assertTrue(policy.shouldWarnNoSpare(8));
        assertTrue(policy.shouldWarnNoSpare(90));
    }

    @Test
    void resetRearmsTheWarning() {
        ElytraPolicy policy = new ElytraPolicy();
        assertTrue(policy.shouldWarnNoSpare(8));
        policy.reset();
        assertTrue(policy.shouldWarnNoSpare(8));
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
}
