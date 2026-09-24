package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the measurement of the real width of the chunk stream (Nether Sweep spec §5 and §10).
 *
 * <p>All the coordinates in these tests are made up and small: what is checked is distance
 * arithmetic, not any concrete place in the world.
 */
class WidthProbeTest {
    /**
     * A ceiling high enough not to discard any sample in the tests that measure something else: 32
     * chunks is Minecraft's maximum render distance, so no legitimate sample goes past it. The
     * ceiling tests use their own, on purpose.
     */
    private static final int CEILING = 32;

    /**
     * The player standing still, which is the only situation in which a sample measures the server's
     * reach and not the drift of the queue. The tests that check something else use it, so as not to
     * drag in the discard for movement; the discard tests bring their own speed.
     */
    private static final double STILL = 0;

    /**
     * An elytra pushed by fireworks: about 33 blocks per second, 1.65 per tick. It is the speed at
     * which a whole sweep is flown and at which every sample comes in inflated by drift.
     */
    private static final double FLYING = 1.65;

    @Test
    void theDistanceIsChebyshevNotEuclidean() {
        // A chunk diagonally at (3,3) from the player measures radius 3 -the side of the square-, not
        // sqrt(3*3+3*3) = 4.24. Measuring it as Euclidean would give a radius larger than the real one
        // on the diagonals and would open gaps right between lanes, which is the failure this test
        // guards against.
        WidthProbe probe = fillToMinimum();

        probe.sample(new ChunkPos(100, 200), new ChunkPos(103, 203), CEILING, STILL);

        assertEquals(3, probe.observedRadiusInChunks());
    }

    @Test
    void anAsymmetricDiagonalIsStillTheLargerOfTheTwoAxes() {
        // dx=2, dz=6: Chebyshev gives 6, not the mean nor the Euclidean (6.32).
        WidthProbe probe = fillToMinimum();

        probe.sample(new ChunkPos(-10, -10), new ChunkPos(-8, -16), CEILING, STILL);

        assertEquals(6, probe.observedRadiusInChunks());
    }

    @Test
    void theResultIsTheObservedMaximumNotTheMean() {
        // Samples {2, 8, 3}: a slow burst that brings nearby chunks cannot narrow the width already
        // seen. The result has to be 8, not the mean (13/3).
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(2, 0), CEILING, STILL);
        }
        probe.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), CEILING, STILL);
        probe.sample(new ChunkPos(0, 0), new ChunkPos(3, 0), CEILING, STILL);

        assertEquals(8, probe.observedRadiusInChunks());
    }

    @Test
    void aLaterSmallerSampleDoesNotLowerTheMaximum() {
        WidthProbe probe = fillToMinimum();
        probe.sample(new ChunkPos(0, 0), new ChunkPos(20, 0), CEILING, STILL);
        int maxAfterTheLargeOne = probe.observedRadiusInChunks();

        probe.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), CEILING, STILL);

        assertEquals(maxAfterTheLargeOne, probe.observedRadiusInChunks());
    }

    // ---------------------------------------------------------------------------------------
    // hasEnoughSamples(): no planning with a figure that does not exist yet
    // ---------------------------------------------------------------------------------------

    @Test
    void withoutSamplesThereAreNotEnough() {
        WidthProbe probe = new WidthProbe();

        assertFalse(probe.hasEnoughSamples());
    }

    @Test
    void withFewSamplesThereAreNotEnough() {
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES - 1; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(5, 5), CEILING, STILL);
        }

        assertFalse(probe.hasEnoughSamples());
    }

    @Test
    void withExactlyTheMinimumSamplesThereAreEnough() {
        WidthProbe probe = fillToMinimum();

        assertTrue(probe.hasEnoughSamples());
    }

    @Test
    void observedRadiusInChunksThrowsIfThereAreNotEnoughSamplesYet() {
        WidthProbe probe = new WidthProbe();
        probe.sample(new ChunkPos(0, 0), new ChunkPos(4, 4), CEILING, STILL);

        assertThrows(IllegalStateException.class, probe::observedRadiusInChunks);
    }

    // ---------------------------------------------------------------------------------------
    // laneWidthInChunks(): observed radius -> safe lane width (spec §5, margin)
    // ---------------------------------------------------------------------------------------

    @Test
    void theWidthIsTwiceTheSafeRadiusRoundedDown() {
        // Radius 7, margin 0.1: safe radius 6.3, width 12.6. It has to come out as 12, not 13 -the
        // rounding always goes towards the narrower width, never towards the wider one.
        WidthProbe probe = probeWithRadius(7);

        assertEquals(12, probe.laneWidthInChunks(0.1));
    }

    @Test
    void aZeroMarginGivesExactlyTwiceTheObservedRadius() {
        WidthProbe probe = probeWithRadius(7);

        assertEquals(14, probe.laneWidthInChunks(0.0));
    }

    @Test
    void aLargerMarginNarrowsTheWidthMore() {
        WidthProbe probe = probeWithRadius(10);

        int withSmallMargin = probe.laneWidthInChunks(0.1);
        int withLargeMargin = probe.laneWidthInChunks(0.4);

        assertTrue(withLargeMargin < withSmallMargin,
            "a larger margin has to give an equal or narrower width, never a wider one");
    }

    @Test
    void laneWidthInChunksThrowsIfThereAreNotEnoughSamplesYet() {
        WidthProbe probe = new WidthProbe();
        probe.sample(new ChunkPos(0, 0), new ChunkPos(4, 4), CEILING, STILL);

        assertThrows(IllegalStateException.class, () -> probe.laneWidthInChunks(0.2));
    }

    @Test
    void aNegativeMarginThrows() {
        WidthProbe probe = probeWithRadius(7);

        assertThrows(IllegalArgumentException.class, () -> probe.laneWidthInChunks(-0.1));
    }

    @Test
    void aMarginOfOneOrMoreThrows() {
        WidthProbe probe = probeWithRadius(7);

        assertThrows(IllegalArgumentException.class, () -> probe.laneWidthInChunks(1.0));
    }

    /** A probe with exactly {@link WidthProbe#MIN_SAMPLES} samples of radius 1. */
    private static WidthProbe fillToMinimum() {
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), CEILING, STILL);
        }
        return probe;
    }

    /** A probe with enough samples and an observed radius of exactly {@code radius}. */
    private static WidthProbe probeWithRadius(int radius) {
        WidthProbe probe = fillToMinimum();
        probe.sample(new ChunkPos(0, 0), new ChunkPos(radius, 0), CEILING, STILL);
        return probe;
    }

    // ---------------------------------------------------------------------------------------
    // The ceiling: a sample above what the client declared is an artifact
    // ---------------------------------------------------------------------------------------

    @Test
    void aSampleAboveTheCeilingNeitherSetsTheMaximumNorCountsAsASample() {
        // The course of the failure: the server queues a batch when the player is at one spot and
        // delivers it ten chunks further on, so that sample measures real radius + 10. With a
        // ceiling of 8 it cannot come from the server, so it is not a measurement of anything.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 8, STILL);
        }

        probe.sample(new ChunkPos(0, 0), new ChunkPos(18, 0), 8, STILL);

        assertEquals(8, probe.observedRadiusInChunks());
        assertEquals(WidthProbe.MIN_SAMPLES, probe.sampleCount());
        assertEquals(1, probe.discardedSamples());
    }

    @Test
    void aLateChunkDoesNotWidenTheLaneForTheRestOfTheSession() {
        // The consequence measured in lane chunks, which is what really opens the gaps: with the
        // artifact in, laneWidthInChunks would give 27 over a server that covers 16.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 8, STILL);
        }
        probe.sample(new ChunkPos(0, 0), new ChunkPos(18, 0), 8, STILL);

        assertEquals(12, probe.laneWidthInChunks(0.25));
    }

    @Test
    void aSampleExactlyAtTheCeilingDoesCount() {
        // The server sends a square of side 2r+1: a chunk at a distance of exactly r is the
        // legitimate edge, not an artifact. Discarding it would narrow the lane for no reason.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(16, 0), 16, STILL);
        }

        assertEquals(16, probe.observedRadiusInChunks());
        assertEquals(0, probe.discardedSamples());
    }

    @Test
    void theCeilingAlsoAppliesDiagonally() {
        // The distance is Chebyshev, so a chunk at (9,9) with a ceiling of 8 goes past it on both
        // axes at once and is discarded just the same.
        WidthProbe probe = new WidthProbe();
        probe.sample(new ChunkPos(0, 0), new ChunkPos(9, 9), 8, STILL);

        assertEquals(0, probe.sampleCount());
        assertEquals(1, probe.discardedSamples());
        assertFalse(probe.hasEnoughSamples());
    }

    @Test
    void onlyArtifactsIsNotAProbeWithAMeasurement() {
        // If everything that arrives is discarded, the probe has to keep saying it does not know,
        // not stick with a radius of 0 that looks like a measurement.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES * 2; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(30, 0), 8, STILL);
        }

        assertFalse(probe.hasEnoughSamples());
        assertThrows(IllegalStateException.class, probe::observedRadiusInChunks);
    }

    @Test
    void aCeilingThatWouldDiscardEverythingIsRejectedInsteadOfSilencingTheProbe() {
        WidthProbe probe = new WidthProbe();

        assertThrows(IllegalArgumentException.class,
            () -> probe.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), 0, STILL));
        assertThrows(IllegalArgumentException.class,
            () -> probe.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), -4, STILL));
    }

    // ---------------------------------------------------------------------------------------
    // Movement: a sample taken while flying measures the server's queue, not its reach
    // ---------------------------------------------------------------------------------------

    @Test
    void aSampleTakenWhileMovingNeitherSetsTheMaximumNorCountsAsASample() {
        WidthProbe probe = new WidthProbe();

        probe.sample(new ChunkPos(0, 0), new ChunkPos(14, 0), 16, FLYING);

        assertEquals(0, probe.sampleCount());
        assertEquals(1, probe.movingSamples());
        assertEquals(0, probe.discardedSamples(), "the discard for movement is not the ceiling one");
        assertFalse(probe.hasEnoughSamples());
    }

    @Test
    void theMeasurementDoesNotRiseWhenTheServerCoversLess() {
        // The whole failure, with the numbers from the class javadoc: ceiling 16, a server that
        // really delivers 8, and the player flying an elytra. Every sample arrives inflated by the
        // drift of the queue -14, 15, 16 chunks- and none goes past the ceiling, so before this fix
        // they all counted and the maximum ended up stuck at 16 just when the real reach was
        // dropping. What is measured has to be what the server covers, not how far the player moved
        // while the packet was queued.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 16, STILL);
        }
        for (int i = 0; i < 20; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(14 + i % 3, 0), 16, FLYING);
        }

        assertEquals(8, probe.observedRadiusInChunks());
        assertEquals(12, probe.laneWidthInChunks(0.2),
            "half a band has to fit within the 8 chunks the server really covers");
        assertEquals(20, probe.movingSamples());
    }

    @Test
    void walkingIsNotFlyingAndItsSamplesCount() {
        // Sprinting is 5.6 blocks per second, 0.28 per tick: below the cap, and at that speed not
        // even a whole second of queue inflates the sample by a chunk. If this did not count, the
        // only way to measure would be to stand absolutely still, and standing still the server
        // sends no new chunks: the probe would never get to measure.
        WidthProbe probe = new WidthProbe();
        for (int i = 0; i < WidthProbe.MIN_SAMPLES; i++) {
            probe.sample(new ChunkPos(0, 0), new ChunkPos(7, 0), 16, 0.28);
        }

        assertTrue(probe.hasEnoughSamples());
        assertEquals(7, probe.observedRadiusInChunks());
        assertEquals(0, probe.movingSamples());
    }

    @Test
    void aSampleExactlyAtTheSpeedCapDoesCount() {
        WidthProbe probe = new WidthProbe();

        probe.sample(new ChunkPos(0, 0), new ChunkPos(5, 0), 16, WidthProbe.MAX_BLOCKS_PER_TICK);

        assertEquals(1, probe.sampleCount());
        assertEquals(0, probe.movingSamples());
    }

    @Test
    void aSpeedThatIsNotASpeedIsRejectedInsteadOfLettingTheSampleThrough() {
        WidthProbe probe = new WidthProbe();

        assertThrows(IllegalArgumentException.class,
            () -> probe.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), 16, -1));
        assertThrows(IllegalArgumentException.class,
            () -> probe.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), 16, Double.NaN));
        assertEquals(0, probe.sampleCount());
    }

    @Test
    void theCeilingCanChangeMidSessionAndEachSampleIsJudgedByItsOwn() {
        // The player lowers their render distance, or the server declares another one: the sample
        // that was legitimate with the old ceiling is discarded with the new one.
        WidthProbe probe = new WidthProbe();
        probe.sample(new ChunkPos(0, 0), new ChunkPos(12, 0), 16, STILL);
        probe.sample(new ChunkPos(0, 0), new ChunkPos(12, 0), 8, STILL);

        assertEquals(1, probe.sampleCount());
        assertEquals(1, probe.discardedSamples());
    }
}
