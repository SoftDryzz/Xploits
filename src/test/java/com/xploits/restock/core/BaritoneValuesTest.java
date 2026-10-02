package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Restock spec §3 "Baritone": an unreadable value refuses, naming the setting as Baritone writes it. */
class BaritoneValuesTest {
    private static Optional<String> unreadable(String... lines) {
        return BaritoneValues.unreadable(BaritoneSession.parse(List.of(lines)));
    }

    @Test
    void aValueBaritoneWouldReadAsSomethingElseIsNamed() {
        assertEquals(Optional.of("allowPlace"), unreadable("allowBreak false", "allowPlace yes"));
        // Baritone lower-cases names and reads "true  " as false (no trim): the player meant true.
        assertEquals(Optional.of("allowBreak"), unreadable("allowbreak true  "));
    }

    @Test
    void readableFilesNameNothing() {
        assertEquals(Optional.empty(), unreadable());
        assertEquals(Optional.empty(), unreadable("allowBreak false", "censorCoordinates true", "# a comment"));
        assertEquals(Optional.empty(), unreadable("allowPlace false  "), "false with spaces is false for Baritone too");
    }

    @Test
    void theFirstInTheOrderRestockSetsThem() {
        assertEquals(Optional.of("censorRanCommands"), unreadable("allowBreak nope", "censorRanCommands maybe"));
    }
}
