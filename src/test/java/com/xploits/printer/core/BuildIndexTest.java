package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static com.xploits.printer.core.BuildIndex.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Printer spec §4 (M12): the incremental index of the whole build; Review Focus 3. */
class BuildIndexTest {
    private static final String STONE = "minecraft:stone";
    private static final String GLASS = "minecraft:glass";

    private static GridBox box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return GridBox.of(new Pos(x1, y1, z1), new Pos(x2, y2, z2));
    }

    @Test
    void countsFollowEverySetIncludingChanges() {
        BuildIndex index = new BuildIndex(List.of(box(0, 0, 0, 2, 0, 0)));
        assertEquals(3, index.counts().unscanned());
        index.set(new Pos(0, 0, 0), Status.MISSING, STONE);
        index.set(new Pos(1, 0, 0), Status.MATCHES, STONE);
        index.set(new Pos(2, 0, 0), Status.WRONG, GLASS);
        assertEquals(new BuildIndex.Counts(0, 0, 0, 0, 1, 0, 1, 1, 0, 0), index.counts());
        assertEquals(Map.of(STONE, 1, GLASS, 1), index.remaining(true));
        assertEquals(Map.of(STONE, 1), index.remaining(false));
        assertEquals(Map.of(STONE, 1), index.placed());
        index.set(new Pos(0, 0, 0), Status.MATCHES, STONE);
        assertEquals(new BuildIndex.Counts(0, 0, 0, 0, 2, 0, 0, 1, 0, 0), index.counts());
        assertEquals(Map.of(STONE, 2), index.placed());
        assertEquals(Status.MATCHES, index.status(new Pos(0, 0, 0)));
        assertEquals(Status.OUTSIDE, index.status(new Pos(5, 0, 0)));
    }

    @Test
    void overlappingBoxesCountEachCellOnce() {
        BuildIndex index = new BuildIndex(List.of(box(0, 0, 0, 2, 0, 0), box(1, 0, 0, 3, 0, 0)));
        assertEquals(4, index.counts().unscanned());
        assertEquals(List.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(2, 0, 0), new Pos(3, 0, 0)), index.nextToScan(10));
        assertEquals(1, index.passes());
        for (int x = 0; x <= 3; x++) index.set(new Pos(x, 0, 0), Status.MATCHES, STONE);
        assertEquals(new BuildIndex.Counts(0, 0, 0, 0, 4, 0, 0, 0, 0, 0), index.counts());
        assertEquals(Map.of(), index.remaining(true));
        assertEquals(4L + 3L, BuildIndex.volume(List.of(box(0, 0, 0, 3, 0, 0), box(1, 0, 0, 3, 0, 0))));
    }

    @Test
    void theScanCursorWalksEveryOwnedCellThenWraps() {
        BuildIndex index = new BuildIndex(List.of(box(0, 0, 0, 1, 1, 0)));
        assertEquals(List.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(0, 1, 0)), index.nextToScan(3));
        assertEquals(0, index.passes());
        assertEquals(List.of(new Pos(1, 1, 0)), index.nextToScan(3), "a call stops where a pass ends");
        assertEquals(1, index.passes());
        assertEquals(List.of(new Pos(0, 0, 0)), index.nextToScan(1));
    }

    @Test
    void theLowestLayerIsTheLowestWithACarriedMaterial() {
        BuildIndex index = new BuildIndex(List.of(box(0, 0, 0, 1, 1, 0)));
        index.set(new Pos(0, 0, 0), Status.MISSING, GLASS);
        index.set(new Pos(1, 1, 0), Status.MISSING, STONE);
        index.set(new Pos(1, 0, 0), Status.WRONG, STONE);
        assertEquals(OptionalInt.of(0), index.lowestLayer(Set.of(STONE), true), "the wrong stone is on layer 0");
        assertEquals(OptionalInt.of(1), index.lowestLayer(Set.of(STONE), false));
        assertEquals(OptionalInt.of(0), index.lowestLayer(Set.of(GLASS), false));
        assertEquals(OptionalInt.empty(), index.lowestLayer(Set.of(), true));
        assertEquals(List.of(new Pos(1, 1, 0)), index.actionableAt(1, Set.of(STONE), true));
        assertEquals(List.of(new Pos(1, 0, 0)), index.actionableAt(0, Set.of(STONE), true));
        assertEquals(List.of(), index.actionableAt(0, Set.of(STONE), false));
    }

    @Test
    void theNearestUnknownPartIsFound() {
        BuildIndex index = new BuildIndex(List.of(box(0, 0, 0, 9, 0, 0)));
        for (int x = 0; x <= 9; x++) index.set(new Pos(x, 0, 0), x >= 6 ? Status.UNKNOWN : Status.MATCHES, STONE);
        assertEquals(Optional.of(new Pos(6, 0, 0)), index.nearestUnknown(new Point(0.5, 0, 0.5)));
        index.set(new Pos(6, 0, 0), Status.MATCHES, STONE);
        assertEquals(Optional.of(new Pos(9, 0, 0)), index.nearestUnknown(new Point(20.5, 0, 0.5)));
    }

    @Test
    void aPositionOutsideTheBuildIsRefused() {
        BuildIndex index = new BuildIndex(List.of(box(0, 0, 0, 1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> index.set(new Pos(5, 0, 0), Status.MATCHES, STONE));
        assertThrows(IllegalArgumentException.class, () -> index.set(new Pos(0, 0, 0), Status.MISSING, null));
    }
}
