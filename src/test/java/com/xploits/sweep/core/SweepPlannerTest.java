package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the lane planner (Nether Sweep spec §10).
 *
 * <p>All the coordinates in these tests are made up and small: what is checked is geometry, not any
 * concrete place in the world.
 */
class SweepPlannerTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    /** A text as the player reads it in Spanish, for the tests that look for words and numbers in it. */
    private static String es(Msg msg) {
        return msg == null ? "null" : ES.render(msg);
    }

    private static final int BLOCKS_PER_CHUNK = 16;

    // ---------------------------------------------------------------------------------------
    // The property that really matters: nothing in the area is left unlooked-at
    // ---------------------------------------------------------------------------------------

    @Test
    void withEmptyCoverageNoChunkOfTheAreaIsFarFromEveryLane() {
        // EVERY chunk of the area is swept and each one is measured against the lanes. Counting the
        // lanes would prove nothing: twenty badly placed lanes leave unseen strips just the same.
        SweepArea area = SweepArea.ofChunks(0, 0, 39, 27);
        int laneWidth = 5;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), laneWidth);

        assertFalse(plan.isRejected());
        assertWholeAreaIsCovered(area, plan.lanes(), laneWidth);
    }

    @Test
    void coverageAlsoHoldsWithAnEvenWidthAndAnIncompleteLastBand() {
        // Even width (the band center falls between two chunks) and an axis that is not a multiple
        // of the width, so the last band comes out narrower than the others.
        SweepArea area = SweepArea.ofChunks(-7, 3, 18, 30);
        int laneWidth = 4;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), laneWidth);

        assertFalse(plan.isRejected());
        assertWholeAreaIsCovered(area, plan.lanes(), laneWidth);
    }

    @Test
    void withTheStackedAxisAnExactMultipleOfTheWidthTheLaneMustRunThroughTheBandCenter() {
        // The two cases above measure the SPACING between lanes, but they barely measure where each
        // one falls: the tolerance (half a width) matches half the spacing, so a lane shifted
        // within its band is covered up by the neighbouring lane. Except in the last band, which
        // has no neighbour on the outside -and both areas above have it short, which is exactly the
        // shape that hides it-.
        //
        // With the stacked axis an exact multiple of the width, the last band is full and its outer
        // edge is exposed: placing the lane at the start of the band instead of at the center
        // leaves that edge (W-1) chunks from the nearest lane, above the W/2 of reach for any
        // W > 2. With W=5 that is two rows of chunks per band left unlooked-at across the whole
        // sweep.
        SweepArea area = SweepArea.ofChunks(0, 0, 39, 19);
        int laneWidth = 5;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), laneWidth);

        assertFalse(plan.isRejected());
        assertEquals(4, plan.lanes().size());
        assertWholeAreaIsCovered(area, plan.lanes(), laneWidth);
    }

    @Test
    void theBandCenterAlsoRulesWithAnEvenWidthAndAnExactMultipleAxis() {
        // The same case with an even width: the band center falls between two chunks and the last
        // band is full, so its outer edge is also left without a neighbour to cover it.
        SweepArea area = SweepArea.ofChunks(0, 0, 39, 15);
        int laneWidth = 4;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), laneWidth);

        assertFalse(plan.isRejected());
        assertEquals(4, plan.lanes().size());
        assertWholeAreaIsCovered(area, plan.lanes(), laneWidth);
    }

    @Test
    void anAreaNarrowerThanALaneGivesOneLaneNotZero() {
        SweepArea area = SweepArea.ofChunks(10, 10, 11, 11);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 16);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
        assertWholeAreaIsCovered(area, plan.lanes(), 16);
    }

    @Test
    void aSingleChunkAreaGivesAFlyableLaneNotADoublePoint() {
        // With the ends on the center of the first and the last chunk of the long axis, a 1x1 area
        // would put them on top of each other: a "lane" of zero length, which is not a flight
        // instruction but a null vector and a target identical to the origin for Baritone. Not
        // emitting it does not work either: that chunk would stay unseen and the sweep would count
        // it as combed anyway.
        SweepArea area = SweepArea.ofChunks(-4, 9, -4, 9);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 8);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
        Lane lane = plan.lanes().get(0);
        assertTrue(lane.fromX() != lane.toX() || lane.fromZ() != lane.toZ(),
            "the lane came out with both ends at the same point: " + lane);
        assertEquals(BLOCKS_PER_CHUNK, lane.lengthInBlocks(), 1e-9,
            "the lane of a single-chunk area should measure the whole chunk: " + lane);
        // And the chunk still falls on the lane: giving it length cannot move the coverage.
        assertEquals(0.0, minDistance(new ChunkPos(-4, 9), plan.lanes()), 1e-9);
        assertEquals(BLOCKS_PER_CHUNK, plan.totalBlocks(), 1e-9);
    }

    // ---------------------------------------------------------------------------------------
    // Planning only over what is left to see (spec §5.1)
    // ---------------------------------------------------------------------------------------

    @Test
    void aFullyCoveredAreaGivesZeroLanesAndIsNotRejected() {
        // There is nothing to fly, and that is not an error: it is the sweep finished.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        Coverage seen = coverageOf(area);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 4);

        assertFalse(plan.isRejected());
        assertTrue(plan.lanes().isEmpty());
        assertEquals(0.0, plan.totalBlocks());
    }

    @Test
    void aHalfCoveredAreaOnlyProducesLanesOverTheGaps() {
        // Square area: the lanes run along X and are stacked along Z. With width 4 there are four
        // bands along Z (0-3, 4-7, 8-11, 12-15) and the first two are fully seen.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        Coverage seen = coverageOf(SweepArea.ofChunks(0, 0, 15, 7));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 4);

        assertFalse(plan.isRejected());
        assertEquals(2, plan.lanes().size());
        for (Lane lane : plan.lanes()) {
            // No lane is wasted on the half already seen.
            assertTrue(lane.fromZ() > 7 * BLOCKS_PER_CHUNK,
                "a lane fell on terrain already seen: " + lane);
        }
    }

    @Test
    void aGapNarrowerThanALaneIsNotIgnored() {
        // A single unseen chunk inside a band of eight. Discarding it for being small would save a
        // whole lane and would leave that chunk marked as combed without ever having looked at it:
        // it is the failure this module cannot make.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        ChunkPos gap = new ChunkPos(5, 3);
        Coverage seen = coverageExcept(area, gap);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 8);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
        assertTrue(minDistance(gap, plan.lanes()) <= 8 / 2.0 * BLOCKS_PER_CHUNK,
            "the only unseen chunk was left out of the lanes' reach");
    }

    @Test
    void aOneChunkGapInEachBandProducesOneLanePerBand() {
        // The same case spread out: two one-chunk gaps, one in each band. Neither is ignored.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        ChunkPos topGap = new ChunkPos(2, 1);
        ChunkPos bottomGap = new ChunkPos(13, 14);
        Coverage seen = coverageExcept(area, topGap, bottomGap);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 8);

        assertEquals(2, plan.lanes().size());
        assertTrue(minDistance(topGap, plan.lanes()) <= 8 / 2.0 * BLOCKS_PER_CHUNK);
        assertTrue(minDistance(bottomGap, plan.lanes()) <= 8 / 2.0 * BLOCKS_PER_CHUNK);
    }

    // ---------------------------------------------------------------------------------------
    // The direction alternates, and it alternates over the lanes that are flown
    // ---------------------------------------------------------------------------------------

    @Test
    void lanesAlternateDirectionToAvoidFlyingBackEmpty() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        assertLanesChain(plan.lanes());
    }

    @Test
    void lanesAlongZAlsoAlternateDirection() {
        // The Z branch of the placement is separate code from the X branch, and without this case
        // nobody looked at it: without alternating, in this area the player would fly more than
        // three thousand blocks empty -three links the whole length of the area- and no test would
        // say so.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 63);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        for (Lane lane : plan.lanes()) {
            assertEquals(lane.fromX(), lane.toX(), "this test assumes lanes parallel to Z");
        }
        assertLanesChain(plan.lanes());
    }

    @Test
    void alongZTheAlternationAlsoCountsFlownLanesNotSkippedBands() {
        // The same parity gap as in the X branch, in the Z branch: a band already seen in the middle.
        SweepArea area = SweepArea.ofChunks(0, 0, 23, 63);
        Coverage seen = coverageOf(SweepArea.ofChunks(4, 0, 7, 63));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 4);

        assertEquals(5, plan.lanes().size());
        assertLanesChain(plan.lanes());
    }

    @Test
    void theAlternationCountsFlownLanesNotSkippedBands() {
        // With a whole band already seen, alternating by band number would leave two consecutive
        // lanes in the same direction and the player would fly back the length of the area empty
        // between them.
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 23);
        Coverage seen = coverageOf(SweepArea.ofChunks(0, 4, 31, 7));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 4);

        assertEquals(5, plan.lanes().size());
        assertLanesChain(plan.lanes());
    }

    // ---------------------------------------------------------------------------------------
    // Lanes run parallel to the long axis
    // ---------------------------------------------------------------------------------------

    @Test
    void inAnAreaWiderThanItIsTallLanesRunAlongX() {
        SweepArea area = SweepArea.ofChunks(0, 0, 63, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        for (Lane lane : plan.lanes()) {
            assertEquals(lane.fromZ(), lane.toZ(), "a lane did not come out parallel to the X axis");
        }
    }

    @Test
    void inAnAreaTallerThanItIsWideLanesRunAlongZ() {
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 63);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        for (Lane lane : plan.lanes()) {
            assertEquals(lane.fromX(), lane.toX(), "a lane did not come out parallel to the Z axis");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Unusable lane width: rejected with a reason, not degraded
    // ---------------------------------------------------------------------------------------

    @Test
    void aZeroLaneWidthIsRejectedInsteadOfDegraded() {
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 0);

        assertTrue(plan.isRejected());
        assertTrue(plan.lanes().isEmpty());
        assertEquals(0.0, plan.totalBlocks());
    }

    @Test
    void aNegativeLaneWidthIsRejectedInsteadOfDegraded() {
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), -3);

        assertTrue(plan.isRejected());
        assertTrue(plan.lanes().isEmpty());
    }

    @Test
    void theRejectionReasonNamesTheSettingItsValueAndWhatToRaiseItTo() {
        // The style of travel/core/RoutePlanner: the reason says what to change and to which value,
        // not only that something is wrong.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        String zeroReason = es(SweepPlanner.plan(area, Coverage.empty(), 0).rejection());
        assertTrue(zeroReason.contains("anchura de pasada"), zeroReason);
        assertTrue(zeroReason.contains("0"), zeroReason);
        assertTrue(zeroReason.contains("1 chunk"), zeroReason);

        String negativeReason = es(SweepPlanner.plan(area, Coverage.empty(), -3).rejection());
        assertTrue(negativeReason.contains("anchura de pasada"), negativeReason);
        assertTrue(negativeReason.contains("-3"), negativeReason);
        assertTrue(negativeReason.contains("1 chunk"), negativeReason);
    }

    @Test
    void theRejectionReasonNamesTheSettingsAsTheyAppearInTheUi() {
        // Naming the setting only in prose -"the lane width"- sends the player to look in the
        // ClickGUI for something that does not exist under that name. The ids this test pins are
        // those of the settings of the sweep/NetherSweep module, and they have to move together.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        String zeroReason = es(SweepPlanner.plan(area, Coverage.empty(), 0).rejection());
        assertTrue(zeroReason.contains("lane-width"), zeroReason);
        assertTrue(zeroReason.contains("lane-width-margin"), zeroReason);

        String negativeReason = es(SweepPlanner.plan(area, Coverage.empty(), -3).rejection());
        assertTrue(negativeReason.contains("lane-width"), negativeReason);
        assertTrue(negativeReason.contains("lane-width-margin"), negativeReason);
    }

    // ---------------------------------------------------------------------------------------
    // The plan as a value
    // ---------------------------------------------------------------------------------------

    @Test
    void totalBlocksCountsTheLinksBetweenLanesNotJustTheLanes() {
        // The firework estimate shown BEFORE takeoff comes from this number (spec §6): if it comes
        // out below the real distance, the module says "you have enough" to someone who does not.
        // The case has TWO consecutive skipped bands on purpose, so that one of the links is long:
        // a case without skipped bands would pass just the same even if the links were not counted.
        //
        // Area 0..31 x 0..23 chunks, wider than tall: lanes along X, stacked along Z. With width 4
        // there are six bands along Z (0-3, 4-7, 8-11, 12-15, 16-19, 20-23) and bands 4-7 and 8-11
        // are fully seen, so four lanes are flown.
        //
        // Band centers along Z, in blocks:  band 0-3 -> 32;  12-15 -> 224;  16-19 -> 288;
        // 20-23 -> 352. The X axis goes from the center of chunk 0 (block 8) to that of chunk 31
        // (block 504), so every lane measures 496 blocks.
        //
        //   4 lanes x 496                                                = 1984
        //   link after skipping two bands:       |224 - 32|             =  192
        //   link between adjacent bands:         |288 - 224|            =   64
        //   link between adjacent bands:         |352 - 288|            =   64
        //                                                          total = 2304
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 23);
        Coverage seen = coverageOf(SweepArea.ofChunks(0, 4, 31, 11));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, seen, 4);

        assertEquals(4, plan.lanes().size());
        assertEquals(2304.0, plan.totalBlocks(), 1e-9);

        double lanesOnly = 0.0;
        for (Lane lane : plan.lanes()) {
            lanesOnly += lane.lengthInBlocks();
        }
        assertEquals(1984.0, lanesOnly, 1e-9);
        assertTrue(plan.totalBlocks() > lanesOnly,
            "totalBlocks stayed at the sum of the lanes: the firework estimate would come out short");
    }

    @Test
    void totalBlocksOfASingleLaneIsThatLaneBecauseThereIsNoLink() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 3);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(1, plan.lanes().size());
        assertEquals(plan.lanes().get(0).lengthInBlocks(), plan.totalBlocks(), 1e-9);
    }

    @Test
    void aRejectedPlanCannotCarryLanes() {
        // Nobody must be able to build a plan that says "rejected" and at the same time carries
        // lanes: whoever read only the list would fly a sweep that had been rejected.
        List<Lane> lanes = List.of(new Lane(0, 0, 100, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new SweepPlanner.SweepPlan(lanes, Msg.of(SweepText.LANE_WIDTH_ZERO)));
    }

    @Test
    void thePlansLaneListIsImmutable() {
        SweepPlanner.SweepPlan plan =
            SweepPlanner.plan(SweepArea.ofChunks(0, 0, 15, 15), Coverage.empty(), 4);

        assertThrows(UnsupportedOperationException.class,
            () -> plan.lanes().add(new Lane(0, 0, 1, 1)));
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Coverage with every chunk of the area marked as seen. */
    private static Coverage coverageOf(SweepArea area) {
        return Coverage.ofLines(linesOf(area, new ChunkPos[0]));
    }

    /** Coverage with every chunk of the area seen except the given gaps. */
    private static Coverage coverageExcept(SweepArea area, ChunkPos... gaps) {
        return Coverage.ofLines(linesOf(area, gaps));
    }

    private static List<String> linesOf(SweepArea area, ChunkPos[] gaps) {
        List<ChunkPos> excluded = List.of(gaps);
        List<String> lines = new ArrayList<>();
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (!excluded.contains(chunk)) {
                    lines.add(x + "," + z);
                }
            }
        }
        return lines;
    }

    /**
     * No chunk of the area can be more than half a lane width away from some lane. It is the whole
     * coverage property: if a single chunk breaks it, there is terrain the sweep would count as
     * combed without having looked at it.
     */
    private static void assertWholeAreaIsCovered(SweepArea area, List<Lane> lanes,
                                                      int laneWidth) {
        double reach = laneWidth / 2.0 * BLOCKS_PER_CHUNK;
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                double distance = minDistance(chunk, lanes);
                assertTrue(distance <= reach + 1e-9,
                    "chunk " + x + "," + z + " ended up " + distance + " blocks from the nearest"
                        + " lane, and a lane's reach is " + reach);
            }
        }
    }

    /**
     * The lanes are flown in sequence, so each one has to start where the previous one ended on the
     * long axis; otherwise the player flies the whole area empty between one lane and the next.
     *
     * <p>It looks at the long axis of each lane, not always X. Comparing {@code toX} with
     * {@code fromX} as is, this helper only says something about areas wider than tall: in those
     * taller than wide the lanes run along Z and their X are different band centers, so the
     * assertion became trivially false -or, had it been loosened, trivially true- and left the Z
     * branch of the alternation unprotected.
     */
    private static void assertLanesChain(List<Lane> lanes) {
        for (int i = 0; i + 1 < lanes.size(); i++) {
            Lane current = lanes.get(i);
            Lane next = lanes.get(i + 1);
            assertTrue(current.fromX() != current.toX() || current.fromZ() != current.toZ(),
                "lane " + i + " goes nowhere: " + current);
            boolean alongX = current.fromZ() == current.toZ();
            String axis = alongX ? "X" : "Z";
            double currentEnd = alongX ? current.toX() : current.toZ();
            double nextStart = alongX ? next.fromX() : next.fromZ();
            assertEquals(currentEnd, nextStart, 1e-9,
                "lane " + (i + 1) + " does not start on " + axis + " where lane " + i
                    + " ended: the player flies the length of the area empty between the two");
            assertEquals(isOutbound(current), !isOutbound(next),
                "two consecutive lanes in the same direction: flying back empty between them");
        }
    }

    private static boolean isOutbound(Lane lane) {
        return lane.fromX() < lane.toX() || lane.fromZ() < lane.toZ();
    }

    /** Distance in blocks from the chunk's center to the nearest lane segment. */
    private static double minDistance(ChunkPos chunk, List<Lane> lanes) {
        double px = chunk.x() * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
        double pz = chunk.z() * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
        double minimum = Double.POSITIVE_INFINITY;
        for (Lane lane : lanes) {
            minimum = Math.min(minimum, distanceToSegment(px, pz, lane));
        }
        return minimum;
    }

    private static double distanceToSegment(double px, double pz, Lane lane) {
        double dx = lane.toX() - lane.fromX();
        double dz = lane.toZ() - lane.fromZ();
        double squaredLength = dx * dx + dz * dz;
        double t = squaredLength == 0.0
            ? 0.0
            : Math.max(0.0, Math.min(1.0,
                ((px - lane.fromX()) * dx + (pz - lane.fromZ()) * dz) / squaredLength));
        return Math.hypot(px - (lane.fromX() + t * dx), pz - (lane.fromZ() + t * dz));
    }
}
