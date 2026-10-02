package com.xploits.restock.core;

import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The positions that hold the build's block with fewer items than it wants (one slab where a double goes). */
class PartlyPlacedTest {
    private static final String SLAB = "minecraft:oak_slab";
    private static final String CANDLE = "minecraft:candle";

    @Test
    void aShortPositionNamesItsMaterialUntilItIsFilled() {
        PartlyPlaced p = new PartlyPlaced();
        p.set(new Pos(0, 0, 0), SLAB);
        p.set(new Pos(1, 0, 0), CANDLE);
        assertEquals(Set.of(SLAB, CANDLE), p.materials());
        p.set(new Pos(0, 0, 0), null);
        assertEquals(Set.of(CANDLE), p.materials(), "the second slab was placed");
        assertEquals(1, p.size());
    }

    @Test
    void aMaterialStaysWhileAnyOfItsPositionsIsShort() {
        PartlyPlaced p = new PartlyPlaced();
        p.set(new Pos(0, 0, 0), SLAB);
        p.set(new Pos(1, 0, 0), SLAB);
        p.set(new Pos(0, 0, 0), null);
        assertEquals(Set.of(SLAB), p.materials());
    }

    @Test
    void clearForgetsEverything() {
        PartlyPlaced p = new PartlyPlaced();
        p.set(new Pos(0, 0, 0), SLAB);
        p.clear();
        assertEquals(Set.of(), p.materials());
        assertEquals(0, p.size());
    }
}
