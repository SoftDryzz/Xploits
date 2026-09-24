package com.xploits.sweep.core;

/**
 * Measures the real width at which the server sends chunks, by observing the stream itself instead
 * of assuming it (Nether Sweep spec §5). Every chunk received is a sample: the distance between the
 * player's position at that instant and the chunk that has just arrived says how far the server
 * reaches right now, on this server and at this speed.
 *
 * <p><b>The distance is Chebyshev</b>, {@code max(|dx|, |dz|)} in chunks, because the server sends
 * in a square, not in a circle. Measuring it as Euclidean would give a radius larger than the real
 * one on the diagonals -a chunk at (3,3) would measure 4.24 instead of 3- and {@link SweepPlanner}
 * would space the lanes further apart than the server really covers, opening gaps right between
 * them: the failure this module cannot make (spec §9), unseen terrain marked as combed.
 *
 * <p><b>The result is the observed maximum, not the mean.</b> A slow burst -the server stalls for an
 * instant and sends a handful of nearby chunks at once- cannot narrow the lane width: what matters is
 * how far the server has got to send, not the average of what has arrived so far.
 *
 * <p><b>And that is exactly why a ceiling is needed.</b> The maximum never going down is right
 * against a slow burst, and it is exactly what turns <b>a single late chunk</b> into a sweep with
 * holes: the server queues a batch when the player is at one spot and delivers it when they are
 * already ten chunks further on, so that sample measures real radius + 10 and stays as the maximum
 * for the rest of the session. With a real radius of 8 chunks, a maximum of 18 gives lanes of 28
 * chunks -448 blocks- over a server that covers 256: a 12-chunk strip between every two lanes that
 * never passes in front of the client and that the sweep announces as combed. It is the lie of spec
 * §9, and a persistent one too: as long as the maximum stays there, every relaunch repeats the same
 * gaps in the same place.
 *
 * <p>The spec already gives the ceiling in §3: <i>"the client's render distance on this instance is
 * 16 chunks… the limit is set by the smaller of the two"</i>. The server cannot send beyond the
 * distance the client declared to it, so <b>every sample above it is demonstrably an artifact</b> and
 * {@link #sample} discards it on entry, without even counting it as a sample: it is not an
 * observation of the server's reach, it is an observation of how far the player moved while the
 * packet was queued.
 *
 * <p><b>But the ceiling only catches the inflated samples that go past it, and that is the rare
 * case.</b> Every sample taken with the player moving measures <i>real radius + how far the player
 * moved while the packet was queued</i>; those that go past the ceiling with that sum are thrown
 * away, and <b>all those that stay below it are accepted as a good measurement</b>. Since the maximum
 * never goes down, the measurement creeps towards the ceiling the more the server lags -more queue,
 * more drift-, and lag is exactly when its effective reach <b>drops</b>: the measurement would rise
 * the less the server covers, inverted and towards the side that opens gaps. The numbers of the
 * normal case are a ceiling of 16 and a server that under load delivers 8: the maximum approaches
 * 16, the width comes out as 25, half a band is 12.5 chunks and the server covers 8. That is 4.5
 * unseen chunks on each side of every lane, 36% of the rectangle, announced as combed.
 *
 * <p><b>That is why the sample also carries how fast the player was going</b>, and it is discarded
 * entirely if they were going faster than {@link #MAX_BLOCKS_PER_TICK}. It is not an approximate
 * correction of the drift -there is no way to know when each packet was queued-, it is refusing to
 * measure when the measurement is contaminated: standing still or walking, the drift of a whole
 * second of queue does not reach one chunk and the observation is of the server's reach; flying an
 * elytra it is three chunks per second of queue and the observation is of the queue, not of the
 * server. Measuring stays cheap -it is enough to walk for a few seconds with the module on, which is
 * exactly what the "not enough samples yet" rejection asks for-, and in exchange flying half an hour
 * towards the area no longer lets in a single inflated sample.
 *
 * <p>This class does not touch Minecraft or Meteor: it receives positions already converted to
 * {@link ChunkPos} and the ceiling already read, it does not query any event or any chunk. What feeds
 * it is the adapter, subscribed to the client's chunk-received event.
 */
public final class WidthProbe {
    /**
     * Minimum samples for {@link #hasEnoughSamples()} to be true.
     *
     * <p>The spec does not set it; it is an implementation decision. With fewer samples than this the
     * observed maximum may be just the first chunk that arrived -normally one of the nearest-, and
     * planning the lane width on that would repeat the failure the spec points out for the chunks
     * loaded at launch (§5): a number that has not had time to settle disguised as a measurement. If
     * real use calls for another value, it is adjusted here, in a single place.
     */
    public static final int MIN_SAMPLES = 8;

    /**
     * How fast at most the player can be going for their sample to count, in blocks per tick, and
     * where the number comes from.
     *
     * <p>What contaminates a sample is the <b>drift</b>: how far the player moved between the server
     * queuing the packet and the client receiving it. It cannot be measured -the packet does not say
     * when it was queued-, but it can be bounded: at {@code v} blocks per tick, a whole second of
     * queue -twenty ticks, which is already a server doing very badly- moves the player {@code 20 v}
     * blocks, that is {@code 20 v / 16} chunks.
     *
     * <p>At 0.5 that bound is 10 blocks, <b>0.63 chunks</b>: below one, so not even with a second of
     * queue can a sample inflate by a whole chunk and widen the lane. And it comfortably lets in
     * everything that is not flight: walking is 4.3 blocks per second (0.215 per tick), sprinting 5.6
     * (0.28) and sprint-jumping about 7.1 (0.36). It also comfortably leaves out the only thing that
     * really inflates: an elytra pushed by fireworks goes at about 33 blocks per second -1.65 per
     * tick, more than three times the cap-, and there one second of queue is more than two chunks of
     * drift.
     */
    public static final double MAX_BLOCKS_PER_TICK = 0.5;

    private int samples = 0;
    private int discardedSamples = 0;
    private int movingSamples = 0;
    private int maxObservedRadius = 0;

    /**
     * Records a chunk received from the server, <b>unless it is an artifact</b>.
     *
     * <p>A sample above {@code maxRadiusInChunks} does not say how far the server sends -it cannot send
     * beyond what the client declared to it-, it says how far the player moved while that packet was
     * queued. It is discarded entirely: it neither sets the maximum nor counts towards
     * {@link #hasEnoughSamples()}, because counting it would be taking as measured something that has
     * not been measured. See the class javadoc for the full course of the failure this closes.
     *
     * <p><b>And a sample taken while moving does not count either</b>, even if it fits under the
     * ceiling: it measures the server's reach plus the player's drift, and the maximum keeps the most
     * inflated of all. See the class javadoc for the full course of that failure and
     * {@link #MAX_BLOCKS_PER_TICK} for where the cap comes from.
     *
     * <p>The ceiling comes with each sample and not in the constructor on purpose: it can change
     * mid-session -the player touches their render distance, or the server declares another one-,
     * and then each observation has to be judged against the ceiling there was when it arrived.
     *
     * @param player             the player's position, in chunks, at the instant it was received
     * @param received           the chunk that has just arrived
     * @param maxRadiusInChunks  the largest radius the server can be sending right now, in chunks;
     *                           above it the sample is an artifact
     * @param blocksPerTick      how far the player moved in the last tick, in blocks; above
     *                           {@link #MAX_BLOCKS_PER_TICK} the sample measures the server's queue
     *                           and not its reach, so it does not count
     * @throws IllegalArgumentException if {@code maxRadiusInChunks} is less than 1 -a ceiling like
     *                                  that would discard absolutely everything and the probe would
     *                                  stay silent forever without anyone knowing why- or if
     *                                  {@code blocksPerTick} is negative or {@code NaN}, which is not
     *                                  a speed and would let the sample through unchecked
     */
    public void sample(ChunkPos player, ChunkPos received, int maxRadiusInChunks, double blocksPerTick) {
        if (maxRadiusInChunks < 1) {
            throw new IllegalArgumentException(
                "the probe's ceiling must be 1 chunk or more (got " + maxRadiusInChunks
                    + "): with less every sample would be discarded and the probe would never get to measure");
        }
        // Written negated so that a NaN -which compares false against everything- falls here and does
        // not slip through the back door as a valid speed.
        if (!(blocksPerTick >= 0)) {
            throw new IllegalArgumentException(
                "the player's speed must be zero or positive (got " + blocksPerTick
                    + "): without a real speed there is no knowing whether the sample comes inflated"
                    + " by drift, and letting it through would be accepting it unchecked");
        }

        if (blocksPerTick > MAX_BLOCKS_PER_TICK) {
            movingSamples++;
            return;
        }

        int distance = Math.max(Math.abs(received.x() - player.x()), Math.abs(received.z() - player.z()));
        if (distance > maxRadiusInChunks) {
            discardedSamples++;
            return;
        }
        maxObservedRadius = Math.max(maxObservedRadius, distance);
        samples++;
    }

    /** How many samples have been discarded for going past the ceiling, so that it can be said. */
    public int discardedSamples() {
        return discardedSamples;
    }

    /**
     * How many samples have been discarded for being taken with the player moving. It is counted
     * apart from {@link #discardedSamples()} because it means something else and is fixed another way:
     * that one is a lagging server, this one is the player flying, who has to stop for a few seconds
     * for the width to be measured.
     */
    public int movingSamples() {
        return movingSamples;
    }

    /** How many good samples the probe has. */
    public int sampleCount() {
        return samples;
    }

    /**
     * The observed radius, in chunks: the maximum of all the recorded samples.
     *
     * <p>It throws if there are not enough samples yet instead of returning a zero or the partial
     * maximum as if it were the final measurement -that would be exactly the made-up number that
     * looks like a measurement which the spec rules out in §6 for the firework estimate, and it
     * applies here just the same: whoever calls this must first check {@link #hasEnoughSamples()}.
     *
     * @throws IllegalStateException if {@link #hasEnoughSamples()} is false
     */
    public int observedRadiusInChunks() {
        if (!hasEnoughSamples()) {
            throw new IllegalStateException(
                "not enough samples yet (" + samples + " of " + MIN_SAMPLES
                    + ") to give an observed radius: check hasEnoughSamples() before calling"
                    + " this");
        }
        return maxObservedRadius;
    }

    /** Whether there are already enough samples for {@link #observedRadiusInChunks()} to mean something. */
    public boolean hasEnoughSamples() {
        return samples >= MIN_SAMPLES;
    }

    /**
     * The safe lane width for {@link SweepPlanner#plan}, from the observed radius and with the spec's
     * safety margin applied (§5: "flying fast can leave gaps even if the nominal distance is right,
     * because chunks take time to arrive").
     *
     * <p><b>Why a margin is needed even though the radius is already a real measurement, not an
     * assumption.</b> {@link #observedRadiusInChunks()} is how far the server got while the samples
     * were being taken, <b>at the speed flown back then</b>. If the sweep is flown faster than that,
     * the player gets ahead of the chunk delivery: by the time the chunk that used to arrive at radius
     * R arrives now, the player is already further on, so the real coverage falls below R. The margin
     * is not a made-up cushion over a measurement that is uncertain anyway -it is the difference
     * between "what arrived at this speed" and "what would have arrived going faster", and only
     * whoever is going to fly the sweep knows how much faster they may go compared with when the
     * samples were taken. That is why {@code safetyMargin} is supplied by the caller, just like
     * {@code reserveFraction} in {@link FuelBudget#willRunOut}: this class cannot guess it, only apply
     * it correctly.
     *
     * <p><b>From radius to width.</b> {@link SweepPlanner} places each lane in the center of its band,
     * so no chunk of the band is more than half a width away from it (see its javadoc); for that half
     * width to fit inside the safe radius, the lane width is <b>twice</b> the radius already reduced
     * by the margin.
     *
     * <p><b>Rounding goes down</b>, never to the nearest nor up. An observed radius on which to round
     * <i>less</i> lane width costs extra lanes -expensive flight, but complete-; rounding <i>more</i>
     * width than the margin allows is the failure this module exists not to make: unseen terrain
     * marked as combed. Faced with the two possible roundings, this method always picks the one that
     * overestimates the risk.
     *
     * @param safetyMargin fraction of the observed radius taken off as margin, in {@code [0, 1)}; 0
     *                      takes off nothing, 0.2 keeps 80% of the radius
     * @return the lane width, in chunks, ready for {@link SweepPlanner#plan}
     * @throws IllegalStateException   if {@link #hasEnoughSamples()} is false -same criterion as
     *                                 {@link #observedRadiusInChunks()}-
     * @throws IllegalArgumentException if {@code safetyMargin} is not in {@code [0, 1)}
     */
    public int laneWidthInChunks(double safetyMargin) {
        if (!(safetyMargin >= 0) || !(safetyMargin < 1)) {
            throw new IllegalArgumentException(
                "safetyMargin must be in [0, 1) (got " + safetyMargin + "): outside"
                    + " that range the safe width comes out zero or negative, which is not a flyable"
                    + " lane width");
        }
        int radius = observedRadiusInChunks();
        double safeRadius = radius * (1 - safetyMargin);
        return (int) Math.floor(2 * safeRadius);
    }
}
