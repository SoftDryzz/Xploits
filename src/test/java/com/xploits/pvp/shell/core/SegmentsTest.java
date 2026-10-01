package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The grid walk that follows a crystal's ray through the blocks around us (surround++ spec §5.3). */
class SegmentsTest {
    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    @Test
    void aLineAlongOneAxisCrossesEveryCellOnTheWay() {
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(2, 0, 0), c(3, 0, 0)),
            Segments.crossed(new Vec(0.5, 0.5, 0.5), new Vec(3.5, 0.5, 0.5)));
    }

    @Test
    void goingBackwardsCrossesThemInReverse() {
        assertEquals(List.of(c(2, 0, 0), c(1, 0, 0), c(0, 0, 0)),
            Segments.crossed(new Vec(2.5, 0.5, 0.5), new Vec(0.5, 0.5, 0.5)));
    }

    @Test
    void aLineInsideOneCellIsThatCell() {
        assertEquals(List.of(c(0, 0, 0)), Segments.crossed(new Vec(0.2, 0.3, 0.4), new Vec(0.8, 0.9, 0.6)));
    }

    @Test
    void aDiagonalStepsOneAxisAtATime() {
        // By hand: x crosses 1 a quarter of the way along, z crosses 1 at half, x crosses 2 at three quarters.
        assertEquals(List.of(c(0, 0, 0), c(1, 0, 0), c(1, 0, 1), c(2, 0, 1)),
            Segments.crossed(new Vec(0.5, 0.5, 0.5), new Vec(2.5, 0.5, 1.5)));
    }

    @Test
    void aRayFromACrystalAtHeadHeightToYourFeetGoesThroughTheWallBlock() {
        // The explosion sits on the bottom face of its cell, so a ray going down leaves it at once, into the block
        // below it, the feet-level wall, and then reaches your own feet cell.
        assertEquals(List.of(c(1, 1, 0), c(1, 0, 0), c(0, 0, 0)),
            Segments.crossed(new Vec(1.5, 1.0, 0.5), new Vec(0.5, 0.2, 0.5)));
    }

    @Test
    void negativeCoordinatesFloorDownwards() {
        assertEquals(List.of(c(-1, 0, 0), c(0, 0, 0)), Segments.crossed(new Vec(-0.5, 0.5, 0.5), new Vec(0.5, 0.5, 0.5)));
    }
}
