package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §4: a mark per container and dimension, with its stand spot; lists give dimension and distance only. */
class MarkBookTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final MarkBook.Mark CHEST = new MarkBook.Mark(OVERWORLD, new Pos(10, 64, 0), new Pos(9, 64, 0));

    @Test
    void pressingAgainUnmarks() {
        MarkBook book = new MarkBook(List.of());
        assertTrue(book.toggle(CHEST));
        assertEquals(1, book.size());
        assertFalse(book.toggle(new MarkBook.Mark(OVERWORLD, new Pos(10, 64, 0), new Pos(11, 64, 0))),
            "the same container from another spot is the same mark");
        assertEquals(0, book.size());
    }

    @Test
    void theSameSpotInAnotherDimensionIsAnotherMark() {
        MarkBook book = new MarkBook(List.of(CHEST));
        assertTrue(book.toggle(new MarkBook.Mark(NETHER, new Pos(10, 64, 0), new Pos(9, 64, 0))));
        assertEquals(2, book.size());
        assertEquals(List.of(CHEST), book.in(OVERWORLD));
    }

    @Test
    void theListGivesDimensionAndRoundedDistanceNearestFirstThenTheOtherDimensions() {
        MarkBook.Mark near = new MarkBook.Mark(OVERWORLD, new Pos(-3, 64, 4), new Pos(-3, 64, 3));
        MarkBook book = new MarkBook(List.of(CHEST, new MarkBook.Mark(NETHER, new Pos(1, 70, 1), new Pos(1, 70, 2)), near));
        // From (0.5, 64.5, 0.5): the chest's centre (10.5, 64.5, 0.5) is 10 away; the near one's (-2.5, 64.5, 4.5) is
        // √(3² + 4²) = 5.
        assertEquals(List.of(new MarkBook.Listed(OVERWORLD, 5), new MarkBook.Listed(OVERWORLD, 10),
            new MarkBook.Listed(NETHER, -1)), book.listed(OVERWORLD, new Point(0.5, 64.5, 0.5)));
    }

    @Test
    void theFileRoundTripsAndAnythingElseIsUnreadable() {
        MarkBook book = new MarkBook(List.of(CHEST, new MarkBook.Mark(NETHER, new Pos(-1, 5, -2), new Pos(-1, 5, -3))));
        assertEquals(List.of("xploits-restock-marks 1", "minecraft:overworld 10 64 0 9 64 0",
            "minecraft:the_nether -1 5 -2 -1 5 -3"), book.toLines());
        assertEquals(book.all(), MarkBook.fromLines(book.toLines()).orElseThrow().all());
        assertEquals(Optional.empty(), MarkBook.fromLines(List.of("something else")));
        assertEquals(Optional.empty(), MarkBook.fromLines(List.of("xploits-restock-marks 1", "minecraft:overworld 1 2")));
        assertEquals(Optional.empty(), MarkBook.fromLines(List.of("xploits-restock-marks 1", "minecraft:overworld a b c d e f")));
        assertEquals(0, MarkBook.fromLines(List.of("xploits-restock-marks 1")).orElseThrow().size());
    }

    @Test
    void clearingSaysHowManyWent() {
        MarkBook book = new MarkBook(List.of(CHEST));
        assertEquals(1, book.clear());
        assertEquals(0, book.size());
    }
}
