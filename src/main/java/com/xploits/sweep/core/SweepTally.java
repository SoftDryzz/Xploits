package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.BitSet;

/**
 * Keeps count of how many chunks of the area have really arrived, so that the sweep can say at the
 * end <b>how much it looked at</b> instead of only that it finished (Nether Sweep spec §9).
 *
 * <p><b>The failure this class closes.</b> The module read the coverage only once, at launch, and
 * never looked at it again: it walked the vertices and, on reaching the last one, announced "the
 * sweep has finished". Between those two things there was no check at all that the rectangle's
 * chunks had arrived. An hour of flight with the server delivering late, or the player getting ahead
 * of the delivery, and a fraction of every band never arrives: the player reads "Sweep finished",
 * crosses the area off and does not come back. It is exactly the one lie the module cannot tell -an
 * incomplete coverage marked as complete- and on top of that it is never discovered.
 *
 * <p><b>And the figure does not have to be assumed.</b> A {@code ChunkDataEvent} arrives for every
 * chunk the server sends, on the same bus the module is already subscribed to in order to measure the
 * lane width. It was enough to look at which of those chunks fall inside the rectangle. This class is
 * that count, in the core and with tests; the adapter only passes it the coordinates as they arrive.
 *
 * <p><b>Why the ones already seen before takeoff are counted too.</b> {@link SweepPlanner} skips the
 * bands that are already fully covered, so those chunks are not going to arrive during the flight
 * and they are not unlooked-at terrain: counting them as gaps would make every sweep over a
 * half-known area look like a failure. They are marked at construction, and so every chunk of the
 * rectangle counts only once, whether it comes from the previous coverage or from today's flight.
 *
 * <p><b>Why a {@link BitSet} and not a set of {@link ChunkPos}.</b> The area can reach
 * {@link SweepArea#MAX_CHUNKS}, four million chunks: a {@code HashSet} of objects there is hundreds of
 * megabytes and a pile of garbage to collect mid-flight, whereas four million bits are half a
 * megabyte and a fixed-cost operation per chunk received. The index arithmetic is the same the walk
 * of the rectangle does, so there is nothing that can get misaligned.
 *
 * <p>This class does not touch Minecraft or Meteor: it receives chunk coordinates already read.
 */
public final class SweepTally {
    private final SweepArea area;
    private final BitSet covered;
    private final int areaChunks;
    private final int alreadySeen;

    private SweepTally(SweepArea area, BitSet covered, int areaChunks, int alreadySeen) {
        this.area = area;
        this.covered = covered;
        this.areaChunks = areaChunks;
        this.alreadySeen = alreadySeen;
    }

    /**
     * Starts the count over an area, marking from the outset the chunks the previous coverage already
     * took as seen.
     *
     * <p>It walks the whole rectangle once, just like {@link Coverage#seenIn(SweepArea)} -in fact
     * {@link #alreadySeen()} gives that same number, so whoever builds this does not need to call
     * that one as well and pay for the walk twice-.
     *
     * @throws NullPointerException if {@code area} or {@code seen} are null
     * @throws ArithmeticException  if the area has more chunks than fit in an {@code int}; the
     *                              adapter rejects it much earlier with
     *                              {@link SweepArea#oversizeRejection()}
     */
    public static SweepTally of(SweepArea area, Coverage seen) {
        if (area == null) throw new NullPointerException("an area is needed to count inside it");
        if (seen == null) {
            throw new NullPointerException("the previous coverage is needed: without it there is no"
                + " knowing which chunks of the area did not need to be seen again");
        }

        int areaChunks = area.chunkCount();
        BitSet covered = new BitSet(areaChunks);
        int alreadySeen = 0;
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                if (seen.seen(new ChunkPos(x, z))) {
                    covered.set(index(area, x, z));
                    alreadySeen++;
                }
            }
        }
        return new SweepTally(area, covered, areaChunks, alreadySeen);
    }

    /**
     * Records a chunk that has just arrived from the server. Those outside the rectangle are ignored
     * -they are most of them during the approach- and repeated ones do not count twice.
     *
     * @return whether this chunk belonged to the area and was not covered yet
     */
    public boolean record(int chunkX, int chunkZ) {
        if (chunkX < area.minChunkX() || chunkX > area.maxChunkX()) return false;
        if (chunkZ < area.minChunkZ() || chunkZ > area.maxChunkZ()) return false;

        int index = index(area, chunkX, chunkZ);
        if (covered.get(index)) return false;
        covered.set(index);
        return true;
    }

    /** How many chunks the rectangle has. */
    public int areaChunks() {
        return areaChunks;
    }

    /** How many chunks of the area were already seen at planning time, according to the previous coverage. */
    public int alreadySeen() {
        return alreadySeen;
    }

    /** How many chunks of the area have arrived during the sweep and had not been seen before. */
    public int arrived() {
        return covered() - alreadySeen;
    }

    /** How many chunks of the area are covered: the earlier ones plus those that have arrived. */
    public int covered() {
        return covered.cardinality();
    }

    /** How many chunks of the area nobody has seen: neither the previous coverage nor this sweep. */
    public int missing() {
        return areaChunks - covered();
    }

    /** Which fraction of the area is covered, in {@code [0, 1]}. */
    public double coveredFraction() {
        return covered() / (double) areaChunks;
    }

    /**
     * Whether the coverage falls below the floor the player considers acceptable. Whoever asks this
     * must warn <b>loudly</b>: a sweep that covered half cannot look like one that covered everything,
     * because both finish and only one has to be repeated.
     *
     * @param floor minimum acceptable fraction, in {@code [0, 1]}
     */
    public boolean shortOfCoverage(double floor) {
        return coveredFraction() < floor;
    }

    /**
     * What to tell the player at the end: how many of the area's N chunks arrived, where they come
     * from and how many are missing.
     *
     * <p><b>The percentage is rounded down</b>, never to the nearest: with 3,590 of 3,600 the sum
     * gives 99.7%, and a "100%" over ten unseen chunks would be the same lie this class exists not to
     * tell, only worded by the rounding. So a 100% only shows up when really none is missing.
     */
    public Msg summary() {
        int percent = (int) Math.floor(coveredFraction() * 100);
        if (missing() == 0) {
            return Msg.of(SweepText.COVERAGE_COMPLETE, "covered", covered(), "total", areaChunks,
                "percent", percent, "seen", alreadySeen, "arrived", arrived());
        }
        return Msg.of(SweepText.COVERAGE_MISSING, "covered", covered(), "total", areaChunks,
            "percent", percent, "seen", alreadySeen, "arrived", arrived(), "missing", missing());
    }

    private static int index(SweepArea area, int chunkX, int chunkZ) {
        return (chunkX - area.minChunkX()) * area.heightInChunks() + (chunkZ - area.minChunkZ());
    }
}
