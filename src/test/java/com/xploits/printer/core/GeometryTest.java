package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {
    @Test
    void facesMoveAndReverseLikeMinecraftsDirections() {
        Pos p = new Pos(3, 64, -7);
        assertEquals(new Pos(3, 63, -7), p.offset(Face.DOWN));
        assertEquals(new Pos(3, 65, -7), p.offset(Face.UP));
        assertEquals(new Pos(3, 64, -8), p.offset(Face.NORTH));
        assertEquals(new Pos(3, 64, -6), p.offset(Face.SOUTH));
        assertEquals(new Pos(2, 64, -7), p.offset(Face.WEST));
        assertEquals(new Pos(4, 64, -7), p.offset(Face.EAST));
        for (Face f : Face.values()) assertEquals(p, p.offset(f).offset(f.opposite()));
        assertEquals("DOWN UP NORTH SOUTH WEST EAST", String.join(" ",
            java.util.Arrays.stream(Face.values()).map(Enum::name).toList()));
    }

    @Test
    void aBlocksCentreAndDistances() {
        assertEquals(new Point(0.5, 0.5, 3.5), new Pos(0, 0, 3).center());
        // eye (0.5, 1.62, 0.5) to the centre of (0, 0, 3): 0 + 1.12^2 + 3^2 = 1.2544 + 9
        assertEquals(10.2544, new Pos(0, 0, 3).distanceSq(new Point(0.5, 1.62, 0.5)), 1e-9);
        assertEquals(5.0, new Point(0, 0, 0).distance(new Point(3, 4, 0)), 1e-12);
    }

    @Test
    void boxesAreNormalisedAndTestedInclusively() {
        GridBox box = GridBox.of(new Pos(2, 5, -1), new Pos(0, 3, 1));
        assertEquals(new Pos(0, 3, -1), box.min());
        assertEquals(new Pos(2, 5, 1), box.max());
        assertEquals(27, box.volume());
        assertEquals(3, box.sizeX());
        assertTrue(box.contains(new Pos(0, 3, -1)));
        assertTrue(box.contains(new Pos(2, 5, 1)));
        assertFalse(box.contains(new Pos(3, 5, 1)));
        assertTrue(box.intersects(GridBox.of(new Pos(2, 5, 1), new Pos(9, 9, 9))));
        assertFalse(box.intersects(GridBox.of(new Pos(3, 5, 1), new Pos(9, 9, 9))));
        // touching at the min corner too, so the other side of every comparison is pinned
        assertTrue(box.intersects(GridBox.of(new Pos(-9, -9, -9), new Pos(0, 3, -1))));
        assertFalse(box.intersects(GridBox.of(new Pos(-9, -9, -9), new Pos(-1, 3, -1))));
        assertThrows(IllegalArgumentException.class, () -> new GridBox(new Pos(1, 0, 0), new Pos(0, 0, 0)));
    }
}
