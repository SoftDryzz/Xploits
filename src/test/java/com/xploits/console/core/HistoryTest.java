package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistoryTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void aMessageWithDateOffsetLevelAndSource() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  INFO   auto-pvp  hola",
            History.line(new LogEntry.Message(0, 0, "s", Level.INFO, "auto-pvp", "hola"), ZoneOffset.UTC, ES));
        assertEquals("1970-01-01 00:00:01.500 +00:00  AVISO  auto-travel  cuidado",
            History.line(new LogEntry.Message(0, 1500, "s", Level.WARNING, "auto-travel", "cuidado"), ZoneOffset.UTC, ES));
    }

    @Test
    void continuationLinesAreIndented() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  ERROR  xploits  uno\n    dos",
            History.line(new LogEntry.Message(0, 0, "s", Level.ERROR, "xploits", "uno\ndos"), ZoneOffset.UTC, ES));
    }

    @Test
    void anEscNeverReachesTheFile() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  INFO   f  a?b",
            History.line(new LogEntry.Message(0, 0, "s", Level.INFO, "f", "a\u001bb"), ZoneOffset.UTC, ES));
    }

    @Test
    void gameAndLostEntriesAreWrittenButSnapshotsAndClosesAreNot() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  JUEGO  inicio",
            History.line(new LogEntry.Game(0, 0, "s", "start"), ZoneOffset.UTC, ES));
        assertEquals("1970-01-01 00:00:00.000 +00:00  AVISO  console  se perdieron 4 mensajes: la cola de la consola se llenó",
            History.line(new LogEntry.Lost(0, 0, "s", 4), ZoneOffset.UTC, ES));
        assertNull(History.line(new LogEntry.Snapshot(0, 0, "s", GameSnapshot.withoutPlayer(List.of(), Language.ES)), ZoneOffset.UTC, ES));
        assertNull(History.line(new LogEntry.Close(0, 0, "s", "l", "m"), ZoneOffset.UTC, ES));
    }

    @Test
    void inEnglishTheLevelsAndTheGameLineToo() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  WARN   auto-travel  careful",
            History.line(new LogEntry.Message(0, 0, "s", Level.WARNING, "auto-travel", "careful"), ZoneOffset.UTC, EN));
        assertEquals("1970-01-01 00:00:00.000 +00:00  GAME   start",
            History.line(new LogEntry.Game(0, 0, "s", "start"), ZoneOffset.UTC, EN));
    }
}
