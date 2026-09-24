package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogEntryTest {
    @Test
    void exactEncodingOfAMessage() {
        assertEquals("R\t7\t1000\ts1\tW\tauto-pvp\ta\\tb",
            new LogEntry.Message(7, 1000, "s1", Level.WARNING, "auto-pvp", "a\tb").encode());
    }

    @Test
    void eachTypeRoundTrips() {
        GameSnapshot snapshot = GameSnapshot.withoutPlayer(List.of(new GameSnapshot.ModuleStatus("auto-pvp", true, "x;y")), Language.EN);
        for (LogEntry e : List.of(
                new LogEntry.Message(1, 2, "s", Level.ERROR, "xploits", "50% \\ end\\"),
                new LogEntry.Snapshot(3, 4, "s", snapshot),
                new LogEntry.Game(5, 6, "s", "start"),
                new LogEntry.Close(7, 8, "s", "l1", "console off"),
                new LogEntry.Lost(9, 10, "s", 42))) {
            assertEquals(e, LogEntry.decode(e.encode()));
        }
    }

    @Test
    void aLoneBackslashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LogEntry.decode("R\t1\t2\ts\tI\tf\ttext\\"));
    }

    @Test
    void tooManyOrTooFewFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LogEntry.decode("J\t1\t2\ts"));
        assertThrows(IllegalArgumentException.class, () -> LogEntry.decode("J\t1\t2\ts\tm\textra"));
    }

    @Test
    void anUnknownTypeIsRejectedByName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> LogEntry.decode("X\t1\t2\ts\tm"));
        assertTrue(e.getMessage().contains("X"));
    }

    @Test
    void aNonNumericSequenceIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> LogEntry.decode("J\tone\t2\ts\tm"));
        assertTrue(e.getMessage().contains("seq"));
    }

    @Test
    void anUnknownLevelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LogEntry.decode("R\t1\t2\ts\tX\tf\tt"));
    }

    @Test
    void theHeaderCarriesVersionAndGeneration() {
        assertEquals("#xploits-console\t3\tabc", LogEntry.header("abc"));
        assertEquals("abc", LogEntry.generationOf(LogEntry.header("abc")));
        assertTrue(LogEntry.isHeader(LogEntry.header("abc")));
        assertFalse(LogEntry.isHeader("R\t1\t2\ts\tI\tf\tt"));
    }

    @Test
    void aHeaderFromAnotherVersionIsRejectedSayingWhich() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> LogEntry.generationOf("#xploits-console\t4\tabc"));
        assertTrue(e.getMessage().contains("4"));
        assertThrows(IllegalArgumentException.class, () -> LogEntry.generationOf("hola"));
    }

    @Test
    void versionOneHeaderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LogEntry.generationOf("#xploits-console\t1\tabc"));
        assertEquals("abc", LogEntry.generationOf("#xploits-console\t3\tabc"));
    }

    @Test
    void versionTwoHeaderIsRejected() {
        // Version 2 is the Spanish-named stream (vivo.log) from before 0.4.0: a window left open across the
        // update must not read it as its own.
        assertThrows(IllegalArgumentException.class, () -> LogEntry.generationOf("#xploits-consola\t2\tabc"));
        assertThrows(IllegalArgumentException.class, () -> LogEntry.generationOf("#xploits-console\t2\tabc"));
        assertFalse(LogEntry.isHeader("#xploits-consola\t2\tabc"));
    }
}
