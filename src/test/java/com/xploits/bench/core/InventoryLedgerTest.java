package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Restock spec §6 "nothing lost from the inventory": what leaves the player and the chests must be found again. */
class InventoryLedgerTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String SHOVEL = "minecraft:diamond_shovel";
    private static final String TORCH = "minecraft:torch";

    @Test
    void aBuildThatUsedItsStoneLosesNothing() {
        // 64 in the hotbar + 32 in the shulker at T0; ten blocks of wall placed; 86 left.
        assertEquals(Map.of(), InventoryLedger.lost(Map.of(STONE, 96L, SHOVEL, 1L), Map.of(STONE, 86L, SHOVEL, 1L),
            Map.of(STONE, 10L)));
    }

    @Test
    void stoneThatDidNotBecomeABlockIsLost() {
        // 64 → 60 is four fewer, but only three blocks appeared: one went somewhere else.
        assertEquals(Map.of(STONE, 1L), InventoryLedger.lost(Map.of(STONE, 64L), Map.of(STONE, 60L), Map.of(STONE, 3L)));
    }

    @Test
    void anItemGoneEntirelyIsLostInFull() {
        assertEquals(Map.of(TORCH, 5L), InventoryLedger.lost(Map.of(STONE, 64L, TORCH, 5L), Map.of(STONE, 64L), Map.of()));
    }

    @Test
    void aPickedUpDropIsNeverALoss() {
        // The wrong dirt block was broken and its drop picked up; one stone replaced it.
        assertEquals(Map.of(), InventoryLedger.lost(Map.of(STONE, 64L), Map.of(STONE, 63L, DIRT, 1L), Map.of(STONE, 1L)));
    }

    @Test
    void theIncreaseKeepsOnlyWhatGrew() {
        // Before: two stone and one dirt in the build; after: five stone, the dirt broken.
        assertEquals(Map.of(STONE, 3L), InventoryLedger.increase(Map.of(STONE, 2L, DIRT, 1L), Map.of(STONE, 5L)));
        assertEquals(Map.of(STONE, 10L), InventoryLedger.increase(Map.of(), Map.of(STONE, 10L)));
        // Still present but fewer than before: not an increase.
        assertEquals(Map.of(), InventoryLedger.increase(Map.of(DIRT, 2L), Map.of(DIRT, 1L)));
    }

    @Test
    void theWordsNameEachItemBeforeItsCount() {
        // A LinkedHashMap with stone first: the words sort by name whatever order the map iterates in.
        Map<String, Long> stoneFirst = new LinkedHashMap<>();
        stoneFirst.put(STONE, 3L);
        stoneFirst.put(DIRT, 1L);
        assertEquals("nothing", InventoryLedger.words(Map.of()));
        assertEquals("minecraft:dirt 1, minecraft:stone 3", InventoryLedger.words(stoneFirst));
    }
}
