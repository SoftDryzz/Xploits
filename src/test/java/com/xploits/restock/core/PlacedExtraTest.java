package com.xploits.restock.core;

import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The sparse tally of items beyond one that placed positions already hold. */
class PlacedExtraTest {
    private static final String SLAB = "minecraft:oak_slab";

    @Test
    void settingAPositionAgainReplacesItsValue() {
        PlacedExtra e = new PlacedExtra();
        e.set(new Pos(0, 0, 0), SLAB, 1);
        e.set(new Pos(0, 0, 0), SLAB, 3);
        assertEquals(Map.of(SLAB, 3), e.byMaterial());
        assertEquals(1, e.size());
    }

    @Test
    void zeroOrNoMaterialRemovesThePosition() {
        PlacedExtra e = new PlacedExtra();
        e.set(new Pos(0, 0, 0), SLAB, 1);
        e.set(new Pos(1, 0, 0), SLAB, 2);
        e.set(new Pos(0, 0, 0), SLAB, 0);
        e.set(new Pos(1, 0, 0), null, 2);
        assertEquals(Map.of(), e.byMaterial());
        assertEquals(0, e.size());
    }

    @Test
    void twoPositionsOfOneMaterialAdd() {
        PlacedExtra e = new PlacedExtra();
        e.set(new Pos(0, 0, 0), SLAB, 1);
        e.set(new Pos(1, 0, 0), SLAB, 2);
        e.set(new Pos(2, 0, 0), "minecraft:candle", 3);
        assertEquals(Map.of(SLAB, 3, "minecraft:candle", 3), e.byMaterial());
        assertEquals(3, e.size());
    }

    @Test
    void clearForgetsEverything() {
        PlacedExtra e = new PlacedExtra();
        e.set(new Pos(0, 0, 0), SLAB, 1);
        e.clear();
        assertEquals(Map.of(), e.byMaterial());
        assertEquals(0, e.size());
    }
}
