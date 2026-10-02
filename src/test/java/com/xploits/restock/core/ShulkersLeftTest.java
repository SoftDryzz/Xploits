package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What a stop leaves at the build is consistent before it is said. */
class ShulkersLeftTest {
    @Test
    void whatIsLeftIsConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new ShulkersLeft(1, -1, -1, false, false, 0),
            "a standing box has a distance");
        assertThrows(IllegalArgumentException.class, () -> new ShulkersLeft(0, 2, -1, false, false, 0),
            "no box standing has no distance");
        assertThrows(IllegalArgumentException.class, () -> new ShulkersLeft(0, -1, 2, true, false, 0),
            "a box on the ground is not lost");
        assertThrows(IllegalArgumentException.class, () -> new ShulkersLeft(0, -1, -1, false, false, -1));
        assertThrows(IllegalArgumentException.class, () -> new ShulkersLeft(0, -1, -1, false, false, 0, true),
            "only a box said to stand may have just broken (M17)");
        assertEquals(new ShulkersLeft(0, -1, -1, false, false, 3), ShulkersLeft.NONE.withBorrowed(3));
        assertEquals(new ShulkersLeft(0, -1, -1, false, true, 0), ShulkersLeft.UNCHECKED);
        assertEquals(new ShulkersLeft(0, -1, -1, false, false, 0, false), ShulkersLeft.NONE,
            "the short form: not breaking");
        assertEquals(new ShulkersLeft(1, 2, -1, false, false, 3, true),
            new ShulkersLeft(1, 2, -1, false, false, 0, true).withBorrowed(3), "the borrowed count keeps the rest");
    }
}
