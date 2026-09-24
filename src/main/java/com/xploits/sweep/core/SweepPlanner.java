package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an area and the coverage the player has already accumulated into the list of lanes to fly
 * (Nether Sweep spec §4 and §5.1). It is the heart of the module: everything else feeds it -the
 * area, the coverage read from {@code NewerNewChunks}, the width measured from the chunk stream- or
 * carries out what comes out of here.
 *
 * <p>Lawnmower geometry, deliberately simple: straight, parallel lanes, separated by the lane width.
 * Nothing to do with {@code travel/core/RoutePlanner}, whose job is exactly the opposite -making the
 * route unpredictable-; here what is wanted is regular coverage with no surprises, and that is why
 * the sweep has its own planner.
 *
 * <p><b>The criterion that settles every doubt in this class:</b> the worst possible failure of the
 * module is not failing to fly, it is counting as combed an area that was never looked at (spec §9).
 * That is never discovered: the player simply does not come back, and the base they were looking for
 * was still there. From that come the two decisions that look like waste and are not:
 *
 * <ul>
 *   <li><b>A gap narrower than a lane is not ignored.</b> It is the tempting optimisation -it looks
 *       like noise and saves a whole lane-, but a single-chunk gap is unseen terrain, and the sweep
 *       would mark it as combed anyway.</li>
 *   <li><b>Lanes run from end to end of the area</b>, even if the gap in that band is a short
 *       stretch. Trimming them to the gap would save flight in exchange for making the coverage of
 *       the edges depend on a figure that may be incomplete; flying too much is cheap, seeing too
 *       little is not.</li>
 * </ul>
 *
 * <p>This class does not touch Minecraft or Meteor: it works on chunks and blocks as numbers.
 */
public final class SweepPlanner {
    private static final int BLOCKS_PER_CHUNK = 16;

    private SweepPlanner() {
    }

    /**
     * The result of planning: either the list of lanes to fly, or a rejection with its reason, just
     * as {@code travel/core/Route} does.
     *
     * <p>With one difference that matters and that is why that record is not reused here: an
     * accepted route with no waypoints means nothing, but <b>an accepted plan with no lanes does</b>.
     * It is the area already fully covered: there is nothing to fly and that is not an error, it is
     * the sweep finished. What cannot exist is the opposite -a rejected plan that carries lanes-,
     * because whoever read only the list would fly a sweep that had been rejected.
     */
    public record SweepPlan(List<Lane> lanes, Msg rejection) {
        public SweepPlan {
            lanes = List.copyOf(lanes);
            if (rejection != null && !lanes.isEmpty()) {
                throw new IllegalArgumentException(
                    "a rejected plan cannot carry lanes: whoever read only the list would fly"
                        + " a sweep that had been rejected");
            }
        }

        /** An accepted plan, with its lanes -which may be none if there is nothing left to see-. */
        public static SweepPlan of(List<Lane> lanes) {
            return new SweepPlan(lanes, null);
        }

        /** A rejected plan: no lanes, with the reason. */
        public static SweepPlan rejected(Msg reason) {
            return new SweepPlan(List.of(), reason);
        }

        public boolean isRejected() {
            return rejection != null;
        }

        /**
         * The blocks of the sweep itself: <b>the length of every lane plus that of the links joining
         * them</b>, that is the stretch <b>from the start of the first lane to the end of the
         * last</b>. A plan with no lanes measures zero, and one with a single lane measures that
         * lane, because there is no link.
         *
         * <p><b>It is not everything the player is going to fly</b>, and whoever estimates fireworks
         * with this has to know it: the approach is missing, the stretch from wherever the player is
         * to the start of the first lane. In a sweep of the kind that justifies this module -an hour
         * from home- that leg is the longest of all, and on the example of spec §6, about 116,000
         * blocks of sweep, it is tens of thousands more. <b>Whoever computes the firework budget must
         * add it separately</b>, and if they also plan to come back, the return too.
         *
         * <p>It cannot be included here: a {@link SweepPlan} is geometry of the area and does not
         * know where the player is. That is why this method promises the sweep and not "the trip",
         * and why it says so in the first line instead of leaving someone to deduce it from the name.
         *
         * <p>The links do count because this number feeds a safety decision: the firework estimate
         * shown <b>before</b> takeoff comes from it (spec §6), and running out of fireworks far from
         * home costs the whole session. A distance below the real one gives too few estimated
         * fireworks, and then the module says "you have enough" to a player who does not: the
         * advance check, which is exactly the one that exists to prevent the trip, would pass
         * falsely. The in-flight measurement catching it half an hour later does not save them,
         * because by then they are already far away.
         *
         * <p>And they are not a rounding error. With lanes from end to end, the link between two
         * consecutive lanes is a perpendicular hop worth one lane width if the bands are adjacent,
         * but as many as the already seen bands skipped in between if not —and skipping bands is
         * the normal case here, because the plan is made over gaps—.
         */
        public double totalBlocks() {
            double total = 0.0;
            for (int i = 0; i < lanes.size(); i++) {
                Lane lane = lanes.get(i);
                total += lane.lengthInBlocks();
                if (i + 1 < lanes.size()) {
                    Lane next = lanes.get(i + 1);
                    total += Math.hypot(next.fromX() - lane.toX(),
                        next.fromZ() - lane.toZ());
                }
            }
            return total;
        }
    }

    /**
     * Plans the sweep of the area, skipping what has already been seen.
     *
     * <p>The lanes run <b>parallel to the long axis</b> of the rectangle and are stacked across the
     * short axis, separated by {@code laneWidthInChunks}. That way there are fewer lanes and fewer
     * turns than stacking them the other way, and every turn is a waypoint where Baritone slows down
     * and lands.
     *
     * <p>Every band of {@code laneWidthInChunks} chunks is looked at in full: if it has any unseen
     * chunk left, it is flown; if it is fully seen, it is skipped. The lane is placed in the center of
     * the band, so no chunk of that band is more than half a lane width away from it.
     *
     * @param area              the rectangle to sweep, in Nether chunks
     * @param seen              the chunks the player has already seen
     * @param laneWidthInChunks spacing between lanes, in chunks; normally measured from the stream
     *                          of chunks the server sends, not typed
     * @return the plan, or a rejection if the lane width is no use for sweeping
     */
    public static SweepPlan plan(SweepArea area, Coverage seen, int laneWidthInChunks) {
        if (laneWidthInChunks <= 0) {
            return SweepPlan.rejected(unusableWidth(laneWidthInChunks));
        }

        boolean lanesAlongX = area.widthInChunks() >= area.heightInChunks();
        int minRun = lanesAlongX ? area.minChunkX() : area.minChunkZ();
        int maxRun = lanesAlongX ? area.maxChunkX() : area.maxChunkZ();
        int minStack = lanesAlongX ? area.minChunkZ() : area.minChunkX();
        int maxStack = lanesAlongX ? area.maxChunkZ() : area.maxChunkX();

        // In long so that a huge area with a lane width of 1 does not overflow the band count before
        // they can be walked.
        long stackedChunks = (long) maxStack - minStack + 1;
        long bands = (stackedChunks + laneWidthInChunks - 1) / laneWidthInChunks;

        List<Lane> lanes = new ArrayList<>();
        for (long band = 0; band < bands; band++) {
            int bandStart = (int) (minStack + band * laneWidthInChunks);
            int bandEnd = (int) Math.min((long) bandStart + laneWidthInChunks - 1, maxStack);
            if (bandFullySeen(seen, lanesAlongX, bandStart, bandEnd, minRun, maxRun)) {
                continue;
            }
            lanes.add(laneForBand(lanesAlongX, bandStart, bandEnd, minRun, maxRun,
                lanes.size()));
        }
        return SweepPlan.of(lanes);
    }

    /**
     * Whether the whole band has already been seen. As soon as an unseen chunk shows up it stops and
     * the band is flown: <b>there is no threshold below which a gap is disregarded</b>. A one-chunk
     * gap is just as much unseen terrain as a hundred-chunk one, and the only difference is that the
     * small one is easier to justify throwing away.
     */
    private static boolean bandFullySeen(Coverage seen, boolean lanesAlongX, int bandStart, int bandEnd,
                                             int minRun, int maxRun) {
        for (int stacked = bandStart; stacked <= bandEnd; stacked++) {
            for (int run = minRun; run <= maxRun; run++) {
                ChunkPos chunk = lanesAlongX
                    ? new ChunkPos(run, stacked)
                    : new ChunkPos(stacked, run);
                if (!seen.seen(chunk)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The lane of a band, centred in it and from end to end of the long axis.
     *
     * <p><b>The direction alternates, and it alternates counting the lanes that are flown, not the
     * bands.</b> The list comes out in the order the adapter is going to fly them, one after the
     * other, so if two consecutive lanes went in the same direction the player would have to fly the
     * whole length of the area empty to get to the start of the second. Alternating by band number
     * looks the same and is not: as soon as a band is skipped for being already seen -which is the
     * normal case here, because the plan is made over gaps- the parity breaks and exactly those empty
     * trips appear. Counting the lanes emitted, each one starts where the previous one ended whatever
     * happens with the bands in between.
     *
     * <p><b>No lane comes out with both ends at the same point.</b> The ends fall on the center of the
     * first and the last chunk of the long axis, so a single-chunk area would put them on top of each
     * other: a "lane" of zero length. Not emitting it would be the failure this module cannot make
     * -that chunk would stay unseen and the sweep would count it as combed anyway-, and handing it
     * over as is does not work either: a zero-length lane is not a flight instruction, it is a null
     * vector the adapter would have to normalise and a target identical to the origin for Baritone.
     * So it is given the minimum length that means something here: the whole chunk, from edge to
     * edge, which is exactly the terrain the server has to be made to send. The center of the chunk
     * still falls on the lane, so the coverage does not change. It can only happen in a 1x1 chunk
     * area: in any other, the long axis measures two chunks or more.
     */
    private static Lane laneForBand(boolean lanesAlongX, int bandStart, int bandEnd, int minRun,
                                         int maxRun, int lanesAlreadyEmitted) {
        boolean outbound = lanesAlreadyEmitted % 2 == 0;
        double center = bandCenterInBlocks(bandStart, bandEnd);
        double runStart = chunkCenterInBlocks(minRun);
        double runEnd = chunkCenterInBlocks(maxRun);
        if (runStart == runEnd) {
            runStart -= BLOCKS_PER_CHUNK / 2.0;
            runEnd += BLOCKS_PER_CHUNK / 2.0;
        }
        double laneStart = outbound ? runStart : runEnd;
        double laneEnd = outbound ? runEnd : runStart;
        return lanesAlongX
            ? new Lane(laneStart, center, laneEnd, center)
            : new Lane(center, laneStart, center, laneEnd);
    }

    /**
     * The center of the band in blocks. With an even width it falls between two chunks, which is
     * right: what has to be minimised is the distance of the band's farthest chunk, not squaring the
     * lane with a grid.
     */
    private static double bandCenterInBlocks(int bandStart, int bandEnd) {
        double centerInChunks = (bandStart + (double) bandEnd) / 2.0;
        return centerInChunks * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
    }

    /** The center of a chunk in blocks, so that the ends of the lane fall on it. */
    private static double chunkCenterInBlocks(int chunk) {
        return chunk * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
    }

    /**
     * The rejection reason for an unusable lane width, in the style of
     * {@code travel/core/RoutePlanner}: it says which setting to change, what it is now and what to
     * raise it to, not only that something is wrong. A rejection that does not say how to get unstuck
     * is almost as bad as silence.
     *
     * <p><b>The settings are named as they appear in Meteor's UI</b> -{@code lane-width} and
     * {@code lane-width-margin} of the {@code nether-sweep} module-, and not only by their name in
     * prose. A reason that only said "raise the lane width" sends the player to look in the ClickGUI
     * for something that does not exist under that name, and then the rejection goes back to being
     * what this text exists not to be: knowing something is wrong and not knowing what to change.
     *
     * <p>And it is rejected instead of degrading to 1 for the usual reason: a sweep flown with a
     * spacing the module makes up leaves unlooked-at strips and marks them as combed anyway.
     * Degrading here would be silently telling the one lie this module cannot tell.
     */
    private static Msg unusableWidth(int laneWidthInChunks) {
        SweepText effect = laneWidthInChunks == 0 ? SweepText.LANE_WIDTH_ZERO : SweepText.LANE_WIDTH_NEGATIVE;
        return Msg.of(SweepText.UNUSABLE_LANE_WIDTH, "width", laneWidthInChunks, "effect", effect);
    }
}
