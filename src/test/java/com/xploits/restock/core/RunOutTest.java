package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §3 "Trigger": a needed material the player no longer carries, in the order they ran out. */
class RunOutTest {
    private static final String STONE = "minecraft:stone";
    private static final String GLASS = "minecraft:glass";
    private static final Map<String, Long> STONE_NEEDED = Map.of(STONE, 10L);

    /** {@link RunOut#due} with every needed material missing at a position the index knows (the usual case). */
    private static List<String> known(RunOut r, long tick, Map<String, Long> need, Map<String, Integer> carried,
                                      int passes) {
        return r.due(tick, need, carried, passes, need.keySet());
    }

    @Test
    void aMaterialIsDueOnlyAfterTwoFullPassesSinceItRanOut() {
        RunOut r = new RunOut(2);
        // It ran out while the scan was in its 5th pass (4 done): that pass may have read the last block before it
        // was placed, so only the pass after it counts.
        assertEquals(List.of(), known(r, 100, STONE_NEEDED, Map.of(), 4));
        assertEquals(List.of(), known(r, 101, STONE_NEEDED, Map.of(), 5));
        assertEquals(List.of(STONE), known(r, 102, STONE_NEEDED, Map.of(), 6));
    }

    @Test
    void nothingIsDueBeforeTheFirstPasses() {
        RunOut r = new RunOut(2);
        assertEquals(List.of(), known(r, 0, STONE_NEEDED, Map.of(), 0));
        assertEquals(List.of(), known(r, 1, STONE_NEEDED, Map.of(), 1));
        assertEquals(List.of(STONE), known(r, 2, STONE_NEEDED, Map.of(), 2));
    }

    @Test
    void dueMaterialsComeInTheOrderTheyRanOut() {
        RunOut r = new RunOut(2);
        known(r, 5, Map.of(STONE, 10L), Map.of(GLASS, 3), 3);
        known(r, 7, Map.of(STONE, 10L, GLASS, 4L), Map.of(), 3);
        assertEquals(List.of(STONE, GLASS), known(r, 9, Map.of(STONE, 10L, GLASS, 4L), Map.of(), 5),
            "stone ran out first, whatever the alphabet says");
    }

    @Test
    void aTieGoesToTheItemId() {
        RunOut r = new RunOut(2);
        Map<String, Long> need = new LinkedHashMap<>();
        need.put(STONE, 10L);
        need.put(GLASS, 4L);
        known(r, 5, need, Map.of(), 0);
        assertEquals(List.of(GLASS, STONE), known(r, 6, need, Map.of(), 2));
    }

    @Test
    void carriedAgainItIsForgottenAndCountsAfreshWhenItRunsOutAgain() {
        RunOut r = new RunOut(2);
        known(r, 0, STONE_NEEDED, Map.of(), 0);
        assertEquals(List.of(STONE), known(r, 1, STONE_NEEDED, Map.of(), 2));
        assertEquals(List.of(), known(r, 2, STONE_NEEDED, Map.of(STONE, 64), 3), "restocked");
        assertEquals(List.of(), known(r, 30, STONE_NEEDED, Map.of(), 10));
        assertEquals(List.of(), known(r, 31, STONE_NEEDED, Map.of(), 11));
        assertEquals(List.of(STONE), known(r, 32, STONE_NEEDED, Map.of(), 12));
    }

    @Test
    void aMaterialTheBuildNoLongerNeedsIsNeverDue() {
        RunOut r = new RunOut(2);
        known(r, 0, STONE_NEEDED, Map.of(), 0);
        assertEquals(List.of(), known(r, 1, Map.of(), Map.of(), 5));
    }

    @Test
    void aMaterialFoundNowhereIsLeftOutUntilTheSourcesChange() {
        RunOut r = new RunOut(2);
        known(r, 0, STONE_NEEDED, Map.of(), 0);
        assertEquals(List.of(STONE), known(r, 1, STONE_NEEDED, Map.of(), 2));
        r.nowhere(STONE);
        assertTrue(r.isNowhere(STONE));
        assertEquals(List.of(), known(r, 2, STONE_NEEDED, Map.of(), 3));
        r.sourcesChanged();
        assertFalse(r.isNowhere(STONE));
        assertEquals(List.of(STONE), known(r, 3, STONE_NEEDED, Map.of(), 4));
    }

    @Test
    void aNowhereMaterialIsTriedAgainOnceItWasCarried() {
        RunOut r = new RunOut(2);
        known(r, 0, STONE_NEEDED, Map.of(), 0);
        known(r, 1, STONE_NEEDED, Map.of(), 2);
        r.nowhere(STONE);
        known(r, 2, STONE_NEEDED, Map.of(STONE, 5), 3);
        assertFalse(r.isNowhere(STONE));
        known(r, 3, STONE_NEEDED, Map.of(), 10);
        assertEquals(List.of(STONE), known(r, 4, STONE_NEEDED, Map.of(), 12));
    }

    @Test
    void aMaterialNeededOnlyAtPositionsTheIndexCannotSeeIsNeverDue() {
        // Ruling R31: Litematica's rendering off, or the far part of the build unloaded, leaves every position of stone
        // unknown. Unknown counts as not built (fetch more, never less), but it must not send a trip for the stone a
        // finished wall already holds.
        RunOut r = new RunOut(2);
        for (int pass = 0; pass <= 10; pass++) {
            assertEquals(List.of(), r.due(pass, STONE_NEEDED, Map.of(), pass, Set.of()), "pass " + pass);
        }
    }

    @Test
    void oneKnownMissingPositionMakesItDueTwoPassesAfterItIsKnown() {
        RunOut r = new RunOut(2);
        assertEquals(List.of(), r.due(0, STONE_NEEDED, Map.of(), 4, Set.of()));
        assertEquals(List.of(), r.due(1, STONE_NEEDED, Map.of(), 6, Set.of()), "only unknown positions for two passes");
        // Rendering back on: this pass finds a missing stone. The two passes count from here, not from when it ran out.
        assertEquals(List.of(), r.due(2, STONE_NEEDED, Map.of(), 6, Set.of(STONE)));
        assertEquals(List.of(), r.due(3, STONE_NEEDED, Map.of(), 7, Set.of(STONE)));
        assertEquals(List.of(STONE), r.due(4, STONE_NEEDED, Map.of(), 8, Set.of(STONE)));
    }

    @Test
    void anotherMaterialsMissingPositionMakesOnlyThatOneDue() {
        RunOut r = new RunOut(2);
        Map<String, Long> need = Map.of(STONE, 10L, GLASS, 4L);
        r.due(0, need, Map.of(), 0, Set.of(GLASS));
        assertEquals(List.of(GLASS), r.due(1, need, Map.of(), 2, Set.of(GLASS)));
    }

    @Test
    void duePassesBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RunOut(0));
    }
}
