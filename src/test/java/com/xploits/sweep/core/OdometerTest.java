package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the flight odometer: a teleport must not count as flight and must not inflate the
 * blocks-per-firework rate (Nether Sweep spec §6).
 *
 * <p>All the distances in these tests are made up: what is checked is arithmetic, not any concrete
 * place in the world.
 */
class OdometerTest {
    @Test
    void withNothingRecordedNothingHasBeenFlown() {
        Odometer odometer = new Odometer();

        assertEquals(0, odometer.blocksFlown());
        assertEquals(0, odometer.jumps());
    }

    @Test
    void normalStepsAreAdded() {
        Odometer odometer = new Odometer();

        assertTrue(odometer.advance(1.6));
        assertTrue(odometer.advance(1.7));

        assertEquals(3.3, odometer.blocksFlown(), 1e-9);
        assertEquals(0, odometer.jumps());
    }

    @Test
    void aJumpIsNeitherAddedNorSpread() {
        Odometer odometer = new Odometer();
        odometer.advance(1.6);

        assertFalse(odometer.advance(4_000));

        assertEquals(1.6, odometer.blocksFlown(), 1e-9);
        assertEquals(1, odometer.jumps());
    }

    @Test
    void aStepExactlyAtTheCapIsStillFlight() {
        Odometer odometer = new Odometer();

        assertTrue(odometer.advance(Odometer.MAX_BLOCKS_PER_TICK));

        assertEquals(Odometer.MAX_BLOCKS_PER_TICK, odometer.blocksFlown(), 1e-9);
        assertEquals(0, odometer.jumps());
    }

    @Test
    void theFastestSustainableFlightIsNotAJump() {
        // Elytra with chained fireworks in a dive, about 60 blocks per second: 3 per tick. If this
        // were discarded, the odometer would not count the real flight and the rate would come out
        // the other way round -fewer blocks per firework than the real ones-, which cuts sweeps
        // that would have made it.
        Odometer odometer = new Odometer();

        assertTrue(odometer.advance(3));

        assertEquals(3, odometer.blocksFlown(), 1e-9);
    }

    @Test
    void theLastStepIsReportedRawEvenIfItIsAJump() {
        // The width probe uses it as the player's speed: a tick with a teleport inside is the worst
        // moment to measure the server's reach, so it has to see it as large and discard the
        // sample, not see it filtered down to zero.
        Odometer odometer = new Odometer();

        odometer.advance(4_000);

        assertEquals(4_000, odometer.lastStep(), 1e-9);
    }

    @Test
    void aDistanceThatIsNotADistanceIsRejected() {
        Odometer odometer = new Odometer();

        assertThrows(IllegalArgumentException.class, () -> odometer.advance(-1));
        assertThrows(IllegalArgumentException.class, () -> odometer.advance(Double.NaN));
        assertEquals(0, odometer.blocksFlown());
    }

    @Test
    void aTeleportDoesNotInflateTheBlocksPerFireworkRate() {
        // The complete failure, up to the decision that depended on it: the player flies 1,000
        // blocks spending 10 fireworks -100 blocks per firework-, goes through a portal that moves
        // them 4,000, and flies another 1,000 spending another 10. Adding the jump, the rate would
        // come out of 6,000 blocks over 20 fireworks: 300 per firework, three times the real one,
        // and with it willRunOut answers that the fireworks will last when they will not.
        Odometer odometer = new Odometer();
        FuelBudget budget = new FuelBudget();
        budget.sample(odometer.blocksFlown(), 100);

        for (int i = 0; i < 625; i++) odometer.advance(1.6);
        budget.sample(odometer.blocksFlown(), 90);
        odometer.advance(4_000);
        for (int i = 0; i < 625; i++) odometer.advance(1.6);
        budget.sample(odometer.blocksFlown(), 80);

        OptionalDouble rate = budget.blocksPerRocket();
        assertTrue(rate.isPresent());
        assertEquals(100, rate.getAsDouble(), 1e-9);
        // And the decision that hangs on it: with 80 fireworks at 100 blocks each there are 8,000
        // blocks of range left, so 9,000 ahead with a 20% reserve do not make it.
        assertTrue(budget.willRunOut(9_000, 80, 0.2),
            "with the real rate the projection cuts; with the one inflated by the jump it would say they last");
    }
}
