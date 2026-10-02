package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Restock spec §3 "Counting": what one block state of the schematic costs in items, never fewer than it consumes. */
class StateItemsTest {
    private static int count(String item, Map<String, String> properties) {
        return StateItems.count(item, properties);
    }

    @Test
    void aPlainBlockIsOneItem() {
        assertEquals(1, count("minecraft:stone", Map.of()));
        assertEquals(1, count("minecraft:oak_log", Map.of("axis", "y")));
    }

    @Test
    void aDoubleSlabIsTwoItemsAndASingleOneIsOne() {
        assertEquals(2, count("minecraft:oak_slab", Map.of("type", "double", "waterlogged", "false")));
        assertEquals(1, count("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "false")));
        assertEquals(1, count("minecraft:oak_slab", Map.of("type", "top", "waterlogged", "true")));
    }

    @Test
    void theUpperHalfOfATwoBlockThingAndABedsHeadAreFree() {
        assertEquals(0, count("minecraft:oak_door", Map.of("half", "upper", "facing", "north", "hinge", "left",
            "open", "false", "powered", "false")));
        assertEquals(1, count("minecraft:oak_door", Map.of("half", "lower", "facing", "north", "hinge", "left",
            "open", "false", "powered", "false")));
        assertEquals(0, count("minecraft:sunflower", Map.of("half", "upper")));
        assertEquals(0, count("minecraft:red_bed", Map.of("part", "head", "facing", "east", "occupied", "false")));
        assertEquals(1, count("minecraft:red_bed", Map.of("part", "foot", "facing", "east", "occupied", "false")));
    }

    @Test
    void stairsAndTrapdoorsOnTheirTopHalfStillCostOne() {
        assertEquals(1, count("minecraft:oak_stairs", Map.of("half", "top", "facing", "west", "shape", "straight",
            "waterlogged", "false")));
        assertEquals(1, count("minecraft:oak_trapdoor", Map.of("half", "top", "facing", "west", "open", "false",
            "powered", "false", "waterlogged", "false")));
    }

    @Test
    void countedStatesCostTheirCount() {
        assertEquals(3, count("minecraft:candle", Map.of("candles", "3", "lit", "false", "waterlogged", "false")));
        assertEquals(4, count("minecraft:sea_pickle", Map.of("pickles", "4", "waterlogged", "true")));
        assertEquals(8, count("minecraft:snow", Map.of("layers", "8")));
        assertEquals(2, count("minecraft:turtle_egg", Map.of("eggs", "2", "hatch", "0")));
        assertEquals(3, count("minecraft:pink_petals", Map.of("flower_amount", "3", "facing", "north")));
        assertEquals(2, count("minecraft:leaf_litter", Map.of("segment_amount", "2", "facing", "north")));
    }

    @Test
    void aBlockNoItemPlacesCostsNothing() {
        assertEquals(0, count(StateItems.NO_ITEM, Map.of("type", "sticky", "facing", "up", "short", "false")));
        assertEquals(0, count(StateItems.NO_ITEM, Map.of("level", "0")));
    }

    @Test
    void aChestHalfIsOneChest() {
        assertEquals(1, count("minecraft:chest", Map.of("type", "left", "facing", "north", "waterlogged", "false")));
    }
}
