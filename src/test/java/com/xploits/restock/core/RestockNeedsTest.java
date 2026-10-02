package com.xploits.restock.core;

import com.xploits.printer.core.BlockFacts;
import com.xploits.printer.core.BuildIndex;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.Pos;
import com.xploits.printer.core.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.UNKNOWN, null),
            RestockNeeds.classify(Target.UNKNOWN, 0, STONE));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.AIR_TARGET, null),
            RestockNeeds.classify(Target.AIR, 0, STONE));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.AIR_TARGET, null),
            RestockNeeds.classify(Target.of(DOOR), 0, BlockFacts.AIR), "a door's upper half costs nothing");
    }

    @Test
    void theSameBlockIsPlacedWhateverItsStateAndAnythingElseIsMissing() {
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MATCHES, "minecraft:stone"),
            RestockNeeds.classify(Target.of(STONE), 1, STONE));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MATCHES, "minecraft:oak_stairs"),
            RestockNeeds.classify(Target.of(STAIRS), 1, STAIRS), "facing is never compared");
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MISSING, "minecraft:stone"),
            RestockNeeds.classify(Target.of(STONE), 1, BlockFacts.AIR));
        assertEquals(new RestockNeeds.Classified(BuildIndex.Status.MISSING, "minecraft:stone"),
            RestockNeeds.classify(Target.of(STONE), 1, DIRT), "a wrong block is not the build");
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
            Map.of("minecraft:stone", 3), Map.of("minecraft:stone", 2, "minecraft:glass", 9)));
    }

    @Test
    void theIndexCountsWhatIsPlacedAndUnknownPartsCountAsNotBuilt() {
        BuildIndex index = new BuildIndex(List.of(GridBox.of(new Pos(0, 0, 0), new Pos(3, 0, 0))));
        set(index, new Pos(0, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, STONE));
        set(index, new Pos(1, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, BlockFacts.AIR));
        set(index, new Pos(2, 0, 0), RestockNeeds.classify(Target.UNKNOWN, 0, BlockFacts.AIR));
        set(index, new Pos(3, 0, 0), RestockNeeds.classify(Target.of(STONE), 1, DIRT));
        assertEquals(Map.of("minecraft:stone", 1), index.placed());
        // The whole build wants 4 stone; 1 is placed and 1 carried: 2 to fetch, the unknown one included.
        assertEquals(Map.of("minecraft:stone", 2L),
            RestockNeeds.need(Map.of("minecraft:stone", 4L), index.placed(), Map.of("minecraft:stone", 1)));
    }

    private static void set(BuildIndex index, Pos pos, RestockNeeds.Classified c) {
        index.set(pos, c.status(), c.material());
    }
}
