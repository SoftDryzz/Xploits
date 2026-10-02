package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3 "Trigger": a needed material the player no longer carries, in the order they ran out. */
class RunOutTest {
    private static final String STONE = "minecraft:stone";
    private static final String GLASS = "minecraft:glass";
    private static final Map<String, Long> STONE_NEEDED = Map.of(STONE, 10L);

    @Test
    void aMaterialIsDueOnlyAfterTwoFullPassesSinceItRanOut() {
        RunOut r = new RunOut(2);
        // It ran out while the scan was in its 5th pass (4 done): that pass may have read the last block before it
        // was placed, so only the pass after it counts.
        assertEquals(List.of(), r.due(100, STONE_NEEDED, Map.of(), 4));
        assertEquals(List.of(), r.due(101, STONE_NEEDED, Map.of(), 5));
        assertEquals(List.of(STONE), r.due(102, STONE_NEEDED, Map.of(), 6));
    }

    @Test
    void nothingIsDueBeforeTheFirstPasses() {
        RunOut r = new RunOut(2);
        assertEquals(List.of(), r.due(0, STONE_NEEDED, Map.of(), 0));
        assertEquals(List.of(), r.due(1, STONE_NEEDED, Map.of(), 1));
        assertEquals(List.of(STONE), r.due(2, STONE_NEEDED, Map.of(), 2));
    }

    @Test
    void dueMaterialsComeInTheOrderTheyRanOut() {
        RunOut r = new RunOut(2);
        r.due(5, Map.of(STONE, 10L), Map.of(GLASS, 3), 3);
        r.due(7, Map.of(STONE, 10L, GLASS, 4L), Map.of(), 3);
        assertEquals(List.of(STONE, GLASS), r.due(9, Map.of(STONE, 10L, GLASS, 4L), Map.of(), 5),
            "stone ran out first, whatever the alphabet says");
    }

    @Test
    void aTieGoesToTheItemId() {
        RunOut r = new RunOut(2);
        Map<String, Long> need = new LinkedHashMap<>();
        need.put(STONE, 10L);
        need.put(GLASS, 4L);
        r.due(5, need, Map.of(), 0);
        assertEquals(List.of(GLASS, STONE), r.due(6, need, Map.of(), 2));
    }

    @Test
    void carriedAgainItIsForgottenAndCountsAfreshWhenItRunsOutAgain() {
        RunOut r = new RunOut(2);
        r.due(0, STONE_NEEDED, Map.of(), 0);
        assertEquals(List.of(STONE), r.due(1, STONE_NEEDED, Map.of(), 2));
        assertEquals(List.of(), r.due(2, STONE_NEEDED, Map.of(STONE, 64), 3), "restocked");
        assertEquals(List.of(), r.due(30, STONE_NEEDED, Map.of(), 10));
        assertEquals(List.of(), r.due(31, STONE_NEEDED, Map.of(), 11));
        assertEquals(List.of(STONE), r.due(32, STONE_NEEDED, Map.of(), 12));
    }

    @Test
    void aMaterialTheBuildNoLongerNeedsIsNeverDue() {
        RunOut r = new RunOut(2);
        r.due(0, STONE_NEEDED, Map.of(), 0);
        assertEquals(List.of(), r.due(1, Map.of(), Map.of(), 5));
    }

    @Test
    void aMaterialFoundNowhereIsLeftOutUntilTheSourcesChange() {
        RunOut r = new RunOut(2);
        r.due(0, STONE_NEEDED, Map.of(), 0);
        assertEquals(List.of(STONE), r.due(1, STONE_NEEDED, Map.of(), 2));
        r.nowhere(STONE);
        assertTrue(r.isNowhere(STONE));
        assertEquals(List.of(), r.due(2, STONE_NEEDED, Map.of(), 3));
        r.sourcesChanged();
        assertFalse(r.isNowhere(STONE));
        assertEquals(List.of(STONE), r.due(3, STONE_NEEDED, Map.of(), 4));
    }

    @Test
    void aNowhereMaterialIsTriedAgainOnceItWasCarried() {
        RunOut r = new RunOut(2);
        r.due(0, STONE_NEEDED, Map.of(), 0);
        r.due(1, STONE_NEEDED, Map.of(), 2);
        r.nowhere(STONE);
        r.due(2, STONE_NEEDED, Map.of(STONE, 5), 3);
        assertFalse(r.isNowhere(STONE));
        r.due(3, STONE_NEEDED, Map.of(), 10);
        assertEquals(List.of(STONE), r.due(4, STONE_NEEDED, Map.of(), 12));
    }

    @Test
    void duePassesBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RunOut(0));
    }
}
