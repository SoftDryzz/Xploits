package com.xploits.sweep.core;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the measurement of real firework spending and its projection (Nether Sweep spec §6 and
 * §10).
 *
 * <p>All the blocks and fireworks in these tests are made up and small: what is checked is the
 * spending arithmetic, not any real flight.
 */
class FuelBudgetTest {

    // ---------------------------------------------------------------------------------------
    // blocksPerRocket()
    // ---------------------------------------------------------------------------------------

    @Test
    void withNoSampleBlocksPerRocketIsEmpty() {
        FuelBudget budget = new FuelBudget();

        assertTrue(budget.blocksPerRocket().isEmpty());
    }

    @Test
    void withASingleSampleBlocksPerRocketIsStillEmpty() {
        // A single point is not a difference: there is no leg to get a spending rate from.
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 20);

        assertTrue(budget.blocksPerRocket().isEmpty());
    }

    @Test
    void withSamplesTheRateIsBlocksFlownOverFireworksSpent() {
        // Reference at (0 blocks, 20 fireworks); after flying 800 blocks 16 are left -4 have been
        // spent-, so the rate is 800/4 = 200 blocks per firework.
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 20);
        budget.sample(800.0, 16);

        OptionalDouble rate = budget.blocksPerRocket();

        assertTrue(rate.isPresent());
        assertEquals(200.0, rate.getAsDouble(), 1e-9);
    }

    @Test
    void spendingAccumulatesOverSeveralLegs() {
        // Two legs of real spending: 400 blocks for 2 fireworks, then 600 blocks for 4 fireworks.
        // Total: 1000 blocks / 6 fireworks.
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 20);
        budget.sample(400.0, 18);
        budget.sample(1000.0, 14);

        assertEquals(1000.0 / 6.0, budget.blocksPerRocket().getAsDouble(), 1e-9);
    }

    @Test
    void restockingMidFlightNeitherProducesNegativeSpendingNorBreaksTheProjection() {
        // The player restocks from 8 to 12 fireworks in the middle leg: that leg is ignored entirely
        // -neither its blocks nor its change in fireworks count-, and the final rate has to match
        // the one that would have come out without the restock: 1000 blocks / 4 fireworks = 250.
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 10);
        budget.sample(500.0, 8);   // spends 2 fireworks in 500 blocks
        budget.sample(600.0, 12);  // restocks: goes up from 8 to 12, leg ignored
        budget.sample(1100.0, 10); // spends 2 more fireworks in 500 blocks

        OptionalDouble rate = budget.blocksPerRocket();

        assertTrue(rate.isPresent());
        assertTrue(rate.getAsDouble() > 0, "the rate cannot come out negative after a restock");
        assertEquals(250.0, rate.getAsDouble(), 1e-9);
    }

    @Test
    void aLegWhereTheFireworkCountStaysTheSameDoesNotCountAsSpending() {
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 10);
        budget.sample(300.0, 10); // flies without spending fireworks -gliding, for example-

        assertTrue(budget.blocksPerRocket().isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // The measured rate expires if too much flight goes by without confirming it (frequent restocks)
    // ---------------------------------------------------------------------------------------

    @Test
    void aMeasuredRateExpiresAfterFlyingUnconfirmedAsFarAsItTookToConfirmIt() {
        // A rate of 400 blocks / 2 fireworks = 200 blocks/firework is confirmed. Then the player
        // restocks often -every following sample raises or keeps the fireworks-, so no leg confirms
        // real spending again. Without expiry, blocksPerRocket() would stay frozen at 200 forever,
        // even if the real consumption afterwards were different.
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 10);
        budget.sample(400.0, 8); // confirms 400/2 = 200

        assertEquals(200.0, budget.blocksPerRocket().getAsDouble(), 1e-9,
            "the rate has to be available right after confirming it");

        budget.sample(700.0, 9);  // restocks: 8 -> 9, ignored, 300 blocks unconfirmed
        budget.sample(900.0, 9);  // stays: 9 -> 9, ignored, 500 blocks unconfirmed

        // 500 unconfirmed blocks exceed the 400 it took to confirm the rate: expired.
        assertTrue(budget.blocksPerRocket().isEmpty(),
            "an old unconfirmed rate cannot keep being used as if it were current");
    }

    @Test
    void exactlyTheFlightItTookToConfirmTheRateDoesNotExpireItYet() {
        // Exact limit: 400 unconfirmed blocks against 400 it took to confirm. It still counts as
        // valid -expiry requires exceeding it, not just matching it-, and one more block passes it.
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 10);
        budget.sample(400.0, 8); // confirms 400/2 = 200
        budget.sample(800.0, 8); // stays: 400 blocks unconfirmed, equal to what was confirmed

        assertTrue(budget.blocksPerRocket().isPresent(),
            "at the exact limit the figure can still be trusted");

        budget.sample(801.0, 8); // one more unconfirmed block and it is already past the limit

        assertTrue(budget.blocksPerRocket().isEmpty());
    }

    @Test
    void aLegWithRealSpendingAfterExpiryRestoresConfidenceImmediately() {
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 10);
        budget.sample(400.0, 8); // confirms 400/2 = 200
        budget.sample(900.0, 8); // 500 unconfirmed: expired

        assertTrue(budget.blocksPerRocket().isEmpty());

        budget.sample(1100.0, 7); // spends 1 firework in 200 blocks: confirms again

        assertTrue(budget.blocksPerRocket().isPresent(),
            "a leg with real spending has to restore confidence even though the previous figure"
                + " had expired");
    }

    @Test
    void willRunOutThrowsIfTheMeasuredRateHasExpired() {
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 10);
        budget.sample(400.0, 8);
        budget.sample(900.0, 8); // 500 unconfirmed: expired

        assertThrows(java.util.NoSuchElementException.class,
            () -> budget.willRunOut(1000.0, 10, 0.2));
    }

    // ---------------------------------------------------------------------------------------
    // willRunOut(): cuts before reaching zero, keeping the reserve
    // ---------------------------------------------------------------------------------------

    @Test
    void withATwentyPercentReserveItCutsWithFireworksStillInHand() {
        // Measured rate: 100 blocks per firework. 5000 blocks left -> 50 fireworks are needed with
        // no reserve; with a 20% reserve the threshold rises to 60. With 55 fireworks in hand -above
        // the bare 50 needed- it must already cut, because 55 < 60.
        FuelBudget budget = budgetAt100BlocksPerFirework();

        assertTrue(budget.willRunOut(5000.0, 55, 0.2));
    }

    @Test
    void withATwentyPercentReserveItDoesNotCutIfThereIsMoreThanNeededPlusTheReserve() {
        FuelBudget budget = budgetAt100BlocksPerFirework();

        assertFalse(budget.willRunOut(5000.0, 65, 0.2));
    }

    @Test
    void withoutAReserveItCutsOnlyWhenBelowWhatIsNeeded() {
        FuelBudget budget = budgetAt100BlocksPerFirework();

        // Exactly 50 fireworks are needed for 5000 blocks at 100 blocks/firework.
        assertFalse(budget.willRunOut(5000.0, 51, 0.0));
        assertTrue(budget.willRunOut(5000.0, 49, 0.0));
    }

    @Test
    void willRunOutThrowsIfThereIsNoRateMeasurementYet() {
        FuelBudget budget = new FuelBudget();

        assertThrows(java.util.NoSuchElementException.class,
            () -> budget.willRunOut(1000.0, 10, 0.2));
    }

    // ---------------------------------------------------------------------------------------
    // A negative reserveFraction would invert the safety guarantee: it is rejected
    // ---------------------------------------------------------------------------------------

    @Test
    void aNegativeReserveThrowsInsteadOfSayingItArrivesWithZeroFireworks() {
        // Real edge case: rate 100 blocks/firework, 5000 blocks ahead -> 50 fireworks are needed.
        // With a reserve of -1.0 the threshold comes out as 50 * (1 + (-1.0)) = 0, so without the
        // validation willRunOut(5000, 0, -1.0) would return false -"no need to cut"- with ZERO
        // fireworks in hand. It has to throw before getting to that calculation.
        FuelBudget budget = budgetAt100BlocksPerFirework();

        assertThrows(IllegalArgumentException.class, () -> budget.willRunOut(5000.0, 0, -1.0));
    }

    @Test
    void aMilderNegativeReserveAlsoThrows() {
        // With -0.5 the threshold drops to 25 instead of rising to 50 + reserve: with 30 fireworks
        // -twenty fewer than the bare number needed- willRunOut would say "no cut" without the
        // validation.
        FuelBudget budget = budgetAt100BlocksPerFirework();

        assertThrows(IllegalArgumentException.class, () -> budget.willRunOut(5000.0, 30, -0.5));
    }

    @Test
    void aNaNReserveAlsoThrows() {
        // NaN always compares as false: rocketsLeft < threshold(NaN) would also be false without the
        // validation, letting the same failure in through another door.
        FuelBudget budget = budgetAt100BlocksPerFirework();

        assertThrows(IllegalArgumentException.class,
            () -> budget.willRunOut(5000.0, 0, Double.NaN));
    }

    @Test
    void aZeroReserveIsStillValid() {
        // Regression: the validation cannot reject the normal "no margin" case.
        FuelBudget budget = budgetAt100BlocksPerFirework();

        assertFalse(budget.willRunOut(5000.0, 51, 0.0));
    }

    /** A budget with a single measured leg: 1000 blocks for 10 fireworks, 100 per firework. */
    private static FuelBudget budgetAt100BlocksPerFirework() {
        FuelBudget budget = new FuelBudget();
        budget.sample(0.0, 30);
        budget.sample(1000.0, 20);
        return budget;
    }
}
