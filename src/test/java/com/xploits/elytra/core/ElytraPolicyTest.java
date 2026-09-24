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
        // min-spare below swap-below: without the rule, this would chain swaps
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
        // threshold 20, minimum 5, worn at 8 % and spares at 10 % and 15 %: it chains 8→10 and then
        // 10→15 before stopping. What the "strictly better" rule guarantees is not that it does not
        // chain, but that there is no endless loop, because the percentage only goes up.
        ElytraPolicy policy = new ElytraPolicy();
        List<ElytraCandidate> spares = List.of(new ElytraCandidate(3, 10), new ElytraCandidate(5, 15));

        ElytraPolicy.Result first = policy.decide(8, spares, 20, 5, 0L);
        assertEquals(Decision.SWAP, first.decision());
        assertEquals(3, first.slot());

        ElytraPolicy.Result second = policy.decide(10, spares, 20, 5, ElytraPolicy.SWAP_COOLDOWN_MS);
        assertEquals(Decision.SWAP, second.decision());
        assertEquals(5, second.slot());

        // At 15 % no strictly better spare is left: it stops here.
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

    // There is no "puttingOnABetterElytraRearmsTheWarning" test calling shouldWarnNoSpare()
    // directly with a higher percentage: the adapter cannot produce that sequence, since it always
    // goes through decide() first, and decide() is already what clears the warning on seeing the
    // percentage rise (rule in one place, spec M4). The real coverage of "putting on a better
    // elytra rearms the warning" comes from gettingABetterElytraWithoutASwapStillRearmsTheWarning,
    // which does walk that path.

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
        // Elytra A at 8 %, no spare: it warns and the 8 is recorded.
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));

        // The player puts on a new elytra B at 95 %, with no qualifying spares: decide() sees the
        // percentage rise even though the decision is OK, and must rearm the warning.
        assertEquals(Decision.OK, policy.decide(95, List.of(), SWAP_BELOW, MIN_SPARE, 2000L).decision());

        // B wears down to the same 8 % with no spares: it is another elytra, so it must warn again,
        // and that happens well inside the 5-minute window: the rearm comes from the rise seen in
        // decide(), not from time.
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
        // Flying at 8 % with no spare: it warns and the 8 is recorded.
        ElytraPolicy policy = new ElytraPolicy();
        assertEquals(Decision.NO_SPARE, policy.decide(8, List.of(), SWAP_BELOW, MIN_SPARE, 1000L).decision());
        assertTrue(policy.shouldWarnNoSpare(8, 1000L));

        // Dies: the chest slot goes empty. decide() must forget the recorded warning, even though
        // the decision itself is NOT_WEARING and not NO_SPARE.
        assertEquals(Decision.NOT_WEARING, policy.decide(null, List.of(), SWAP_BELOW, MIN_SPARE, 1500L).decision());

        // Respawns and puts on the only elytra it had, at 5 %: worse than the previous one (8 %),
        // so without the empty-chest-slot rearm it would never warn. It must warn all the same.
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
        // swapBelow = 10 and the worn one also at 10 %: the boundary is inclusive, it is not OK.
        assertEquals(Decision.SWAP, decide(10, List.of(new ElytraCandidate(3, 90)), 1000L).decision());
    }

    @Test
    void percentOfRoundsDownOnAnInexactDivision() {
        assertEquals(66, ElytraPolicy.percentOf(1, 3));
    }
}
