package com.xploits.restock.core;

import com.xploits.printer.core.BlockFacts;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3 "Counting": need = schematic total − placed − carried; unknown parts count as not built. */
class RestockNeedsTest {
    private static final BlockFacts STONE = new BlockFacts("minecraft:stone", "minecraft:stone", false, true, true, true,
        false, false, false, false, false, false);
    private static final BlockFacts DIRT = new BlockFacts("minecraft:dirt", "minecraft:dirt", false, true, true, true,
        false, false, false, false, false, false);
    private static final BlockFacts DOOR = new BlockFacts("minecraft:oak_door", "minecraft:oak_door", false, false, true,
        false, false, false, false, false, false, false);
    private static final BlockFacts STAIRS = new BlockFacts("minecraft:oak_stairs", "minecraft:oak_stairs", false, false,
        true, false, false, false, true, false, false, false);

    @Test
    void unknownAndAirAreNeverMaterial() {
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.UNKNOWN, null, 0),
            RestockNeeds.classify(Target.UNKNOWN, 0, STONE, 1));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.AIR_TARGET, null, 0),
            RestockNeeds.classify(Target.AIR, 0, STONE, 1));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.AIR_TARGET, null, 0),
            RestockNeeds.classify(Target.of(DOOR), 0, BlockFacts.AIR, 0), "a door's upper half costs nothing");
    }

    @Test
    void theSameBlockIsPlacedWhateverItsStateAndAnythingElseIsMissing() {
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MATCHES, "minecraft:stone", 0),
            RestockNeeds.classify(Target.of(STONE), 1, STONE, 1));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MATCHES, "minecraft:oak_stairs", 0),
            RestockNeeds.classify(Target.of(STAIRS), 1, STAIRS, 1), "facing is never compared");
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MISSING, "minecraft:stone", 0),
            RestockNeeds.classify(Target.of(STONE), 1, BlockFacts.AIR, 0));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MISSING, "minecraft:stone", 0),
            RestockNeeds.classify(Target.of(STONE), 1, DIRT, 1), "a wrong block is not the build");
    }

    @Test
    void totalsMultiplyEachStateByItsItemsAndSkipFreeOnes() {
        assertEquals(Map.of("minecraft:oak_slab", 16L, "minecraft:oak_door", 2L), RestockNeeds.totals(List.of(
            new RestockNeeds.Counted("minecraft:oak_slab", 1, 10),
            new RestockNeeds.Counted("minecraft:oak_slab", 2, 3),
            new RestockNeeds.Counted("minecraft:oak_door", 1, 2),
            new RestockNeeds.Counted("minecraft:oak_door", 0, 2),
            new RestockNeeds.Counted(StateItems.NO_ITEM, 0, 5),
            new RestockNeeds.Counted("minecraft:stone", 1, 0))));
    }

    @Test
    void theNeedIsTheTotalLessWhatIsPlacedAndWhatIsCarried() {
        // stone: 10 - 3 - 2 = 5; glass: 4 - 0 - 9 is negative, so not needed.
        assertEquals(Map.of("minecraft:stone", 5L), RestockNeeds.need(Map.of("minecraft:stone", 10L, "minecraft:glass", 4L),
            Map.of("minecraft:stone", 3), Map.of(), Map.of("minecraft:stone", 2, "minecraft:glass", 9)));
    }

    @Test
    void theIndexCountsWhatIsPlacedAndUnknownPartsCountAsNotBuilt() {
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(3, 0, 0))));
        set(index, new Pos(0, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, STONE, 1));
        set(index, new Pos(1, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, BlockFacts.AIR, 0));
        set(index, new Pos(2, 0, 0), RestockNeeds.classify(Target.UNKNOWN, 0, BlockFacts.AIR, 0));
        set(index, new Pos(3, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, DIRT, 1));
        assertEquals(Map.of("minecraft:stone", 1), index.placed());
        // The whole build wants 4 stone; 1 is placed and 1 carried: 2 to fetch, the unknown one included.
        assertEquals(Map.of("minecraft:stone", 2L),
            RestockNeeds.need(Map.of("minecraft:stone", 4L), index.placed(), Map.of(), Map.of("minecraft:stone", 1)));
    }

    @Test
    void onlyAKnownMissingPositionMakesAMaterialDueButTheUnknownOnesAreStillTaken() {
        // Ruling R31: one stone placed, one known missing, two unknown (Litematica's rendering off for that part). The
        // index's missing positions, which decide whether stone is due, list only the known one; the need, which is
        // how much a trip takes, still counts the unknown ones too: fetch more, never less.
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(3, 0, 0))));
        set(index, new Pos(0, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, STONE, 1));
        set(index, new Pos(1, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, BlockFacts.AIR, 0));
        set(index, new Pos(2, 0, 0), RestockNeeds.classify(Target.UNKNOWN, 0, BlockFacts.AIR, 0));
        set(index, new Pos(3, 0, 0), RestockNeeds.classify(Target.UNKNOWN, 0, STONE, 1));
        assertEquals(Map.of("minecraft:stone", 1), index.remaining(false));
        assertEquals(Map.of("minecraft:stone", 3L),
            RestockNeeds.need(Map.of("minecraft:stone", 4L), index.placed(), Map.of(), Map.of()));
    }

    @Test
    void aFinishedDoubleSlabOrCountedStateNeedsNothingMore() {
        String slab = "minecraft:oak_slab";
        String candle = "minecraft:candle";
        BlockFacts slabFacts = new BlockFacts(slab, slab, false, false, true, false, false, false, false, false, false, false);
        BlockFacts candleFacts = new BlockFacts(candle, candle, false, false, true, false, false, false, false, false, false, false);
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(1, 0, 0))));
        PlacedExtra extra = new PlacedExtra();
        Pos slabPos = new Pos(0, 0, 0);
        Pos candlePos = new Pos(1, 0, 0);
        RestockNeeds.Classified a = RestockNeeds.classify(Target.of(slabFacts), 2, slabFacts, 2);
        RestockNeeds.Classified b = RestockNeeds.classify(Target.of(candleFacts), 4, candleFacts, 4);
        set(index, slabPos, a);
        set(index, candlePos, b);
        extra.set(slabPos, a.material(), a.extra());
        extra.set(candlePos, b.material(), b.extra());
        assertEquals(Map.of(slab, 1, candle, 3), extra.byMaterial());
        assertEquals(Map.of(), RestockNeeds.need(Map.of(slab, 2L, candle, 4L), index.placed(), extra.byMaterial(), Map.of()));
    }

    @Test
    void aSingleSlabWhereADoubleGoesStillNeedsOne() {
        BlockFacts slab = new BlockFacts("minecraft:oak_slab", "minecraft:oak_slab", false, false, true, false, false,
            false, false, false, false, false);
        RestockNeeds.Classified c = RestockNeeds.classify(Target.of(slab), 2, slab, 1);
        assertEquals(0, c.extra());
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(0, 0, 0))));
        set(index, new Pos(0, 0, 0), c);
        assertEquals(Map.of("minecraft:oak_slab", 1L), RestockNeeds.need(Map.of("minecraft:oak_slab", 2L),
            index.placed(), Map.of(), Map.of()));
    }

    @Test
    void aSingleSlabTargetWhereTheWorldHoldsADoubleOneHasNoExtra() {
        BlockFacts slab = new BlockFacts("minecraft:oak_slab", "minecraft:oak_slab", false, false, true, false, false,
            false, false, false, false, false);
        assertEquals(0, RestockNeeds.classify(Target.of(slab), 1, slab, 2).extra());
    }

    @Test
    void aMatchingPositionWithFewerItemsThanTheBuildWantsIsPartial() {
        BlockFacts slab = new BlockFacts("minecraft:oak_slab", "minecraft:oak_slab", false, false, true, false, false,
            false, false, false, false, false);
        BlockFacts candle = new BlockFacts("minecraft:candle", "minecraft:candle", false, false, true, false, false,
            false, false, false, false, false);
        assertTrue(RestockNeeds.classify(Target.of(slab), 2, slab, 1).partial(), "one slab where a double goes");
        assertTrue(RestockNeeds.classify(Target.of(candle), 4, candle, 2).partial(), "two of four candles");
        assertFalse(RestockNeeds.classify(Target.of(slab), 2, slab, 2).partial(), "a finished double slab");
        assertFalse(RestockNeeds.classify(Target.of(slab), 1, slab, 2).partial(), "more than the build wants");
        assertFalse(RestockNeeds.classify(Target.of(STONE), 1, STONE, 1).partial());
        assertFalse(RestockNeeds.classify(Target.of(slab), 2, BlockFacts.AIR, 0).partial(), "missing, not partial");
        assertFalse(RestockNeeds.classify(Target.UNKNOWN, 0, slab, 1).partial());
    }

    @Test
    void aMaterialShortOnlyInPartlyFilledPositionsIsKnownMissing() {
        // Deferred m1 (after ruling R31): every slab position holds at least one slab, so the index lists no missing
        // slab, yet the need sits in the position that holds one where a double goes. It must still make slabs due.
        BlockFacts slab = new BlockFacts("minecraft:oak_slab", "minecraft:oak_slab", false, false, true, false, false,
            false, false, false, false, false);
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(2, 0, 0))));
        PlacedExtra extra = new PlacedExtra();
        PartlyPlaced partly = new PartlyPlaced();
        classify(index, extra, partly, new Pos(0, 0, 0), RestockNeeds.classify(Target.of(slab), 2, slab, 2));
        classify(index, extra, partly, new Pos(1, 0, 0), RestockNeeds.classify(Target.of(slab), 2, slab, 1));
        classify(index, extra, partly, new Pos(2, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, BlockFacts.AIR, 0));
        assertEquals(Map.of("minecraft:stone", 1), index.remaining(false), "the index sees no missing slab");
        Map<String, Long> need = RestockNeeds.need(Map.of("minecraft:oak_slab", 4L, "minecraft:stone", 1L),
            index.placed(), extra.byMaterial(), Map.of());
        assertEquals(Map.of("minecraft:oak_slab", 1L, "minecraft:stone", 1L), need);
        assertEquals(Set.of("minecraft:oak_slab", "minecraft:stone"), RestockNeeds.knownMissing(index, partly));
        RunOut r = new RunOut(2);
        r.due(0, need, Map.of(), 0, RestockNeeds.knownMissing(index, partly));
        assertEquals(List.of("minecraft:oak_slab", "minecraft:stone"),
            r.due(1, need, Map.of(), 2, RestockNeeds.knownMissing(index, partly)));
    }

    @Test
    void theBuildIsDoneOnlyOnceEveryItemIsPlacedTheItemsBeyondOneIncluded() {
        // Pre-flight 19-11 (ruling R6): two double slabs hold four slabs in two matching positions, and the build is
        // done. Without the items beyond one it would never be done, and no last trip would ever take a borrowed shulker
        // box back.
        Map<String, Long> totals = Map.of("minecraft:oak_slab", 4L, "minecraft:stone", 2L);
        Map<String, Integer> placed = Map.of("minecraft:oak_slab", 2, "minecraft:stone", 2);
        assertEquals(0, RestockNeeds.remaining(totals, placed, Map.of("minecraft:oak_slab", 2)));
        assertEquals(2, RestockNeeds.remaining(totals, placed, Map.of()), "two single slabs where doubles go");
        assertEquals(6, RestockNeeds.remaining(totals, Map.of(), Map.of()), "nothing placed: the whole build");
    }

    @Test
    void aMaterialPlacedBeyondItsTotalNeverHidesAnotherOnesShortfall() {
        // Stone placed beyond its total (the player built more by hand) cannot make up for the glass still missing.
        assertEquals(2, RestockNeeds.remaining(Map.of("minecraft:stone", 2L, "minecraft:glass", 3L),
            Map.of("minecraft:stone", 5, "minecraft:glass", 1), Map.of()));
    }

    @Test
    void aFinishedBuildKnowsNothingMissing() {
        BlockFacts slab = new BlockFacts("minecraft:oak_slab", "minecraft:oak_slab", false, false, true, false, false,
            false, false, false, false, false);
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(0, 0, 0))));
        PartlyPlaced partly = new PartlyPlaced();
        classify(index, new PlacedExtra(), partly, new Pos(0, 0, 0), RestockNeeds.classify(Target.of(slab), 2, slab, 2));
        assertEquals(Set.of(), RestockNeeds.knownMissing(index, partly));
    }

    /** As the session does: the index, the items beyond one, and the short positions, from one classification. */
    private static void classify(BuildIndex index, PlacedExtra extra, PartlyPlaced partly, Pos pos,
                                 RestockNeeds.Classified c) {
        set(index, pos, c);
        extra.set(pos, c.material(), c.extra());
        partly.set(pos, c.partial() ? c.material() : null);
    }

    private static void set(BuildIndex index, Pos pos, RestockNeeds.Classified c) {
        index.set(pos, c.status(), c.material());
    }
}
