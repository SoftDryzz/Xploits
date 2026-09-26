package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenFrameTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final List<String> ART = Collections.nCopies(12, "X".repeat(99) + Ansi.RESET);
    private static final GameSnapshot SNAPSHOT = new GameSnapshot("minecraft:overworld", 2, 1, 64, 90, null, null,
        20.0, 20, 64, 12, 4, 1, List.of(new GameSnapshot.ModuleStatus("auto-pvp", true, "SUPERFICIE")), Language.ES);
    private static final Heartbeat.GameState ALIVE = new Heartbeat.GameState.Alive();

    private static ScreenFrame.Input input(int cols, int rows, GameSnapshot snapshot, Heartbeat.GameState game,
                                           List<LogEntry.Message> messages, LogFilter filter, boolean paused, int newMessages) {
        return input(cols, rows, snapshot, game, messages, filter, paused, newMessages, ES);
    }

    private static ScreenFrame.Input input(int cols, int rows, GameSnapshot snapshot, Heartbeat.GameState game,
                                           List<LogEntry.Message> messages, LogFilter filter, boolean paused, int newMessages,
                                           Catalog texts) {
        return new ScreenFrame.Input(new WindowSize(cols, rows), ART, snapshot, game, messages, filter, paused, newMessages, null,
            Glyphs.UNICODE, ZoneOffset.UTC, texts);
    }

    private static List<String> paint(int cols, int rows) {
        return stripColor(ScreenFrame.compose(input(cols, rows, SNAPSHOT, ALIVE, List.of(), LogFilter.ALL, false, 0)));
    }

    private static List<String> stripColor(List<String> rows) {
        return rows.stream().map(Ansi::stripColor).toList();
    }

    @Test
    void atTheRequestedSizeEverythingFits() {
        List<String> f = paint(110, 46);
        assertEquals(45, f.size());
        assertEquals("X".repeat(99), f.get(0));
        assertEquals("X".repeat(99), f.get(11));
        assertEquals("─".repeat(110), f.get(12));
        assertEquals("dimensión overworld · jugadores cargados 2 · nuestros 1", f.get(13));
        assertEquals("● auto-pvp SUPERFICIE", f.get(16));
        assertEquals("─".repeat(110), f.get(17));
        assertEquals("", f.get(41));
        assertEquals("─".repeat(110), f.get(42));
        assertEquals(Menu.line(ES), f.get(43));
        assertEquals("filtro: todo · juego conectado", f.get(44));
    }

    @Test
    void noRowIsWiderThanTheWindowAndNoneIsMissingOrExtra() {
        List<LogEntry.Message> longOnes = List.of(new LogEntry.Message(0, 0, "s", Level.INFO, "auto-pvp", "x".repeat(300)));
        for (WindowSize s : List.of(new WindowSize(60, 12), new WindowSize(80, 20), new WindowSize(110, 46), new WindowSize(200, 60))) {
            List<String> f = stripColor(ScreenFrame.compose(input(s.cols(), s.rows(), SNAPSHOT, ALIVE, longOnes, LogFilter.ALL, false, 0)));
            assertEquals(s.rows() - 1, f.size(), s.toString());
            for (String row : f) assertTrue(TerminalText.width(row) <= s.cols(), s + ": " + row);
        }
    }

    @Test
    void withoutRoomForTheLogoTheNameIsShownAndSaid() {
        List<String> f = paint(99, 46);
        assertEquals(45, f.size());
        assertEquals("Xploits", f.get(0));
        assertEquals("filtro: todo · juego conectado · ocultas: logo", f.get(44));
    }

    @Test
    void withFewRowsDataIsRemovedInOrderAndSaid() {
        List<String> f = paint(80, 14);
        assertEquals(13, f.size());
        assertTrue(f.get(2).startsWith("cohetes 64"), f.get(2));
        assertTrue(f.get(3).startsWith("vida 20,0"), f.get(3));
        assertEquals("filtro: todo · juego conectado · ocultas: logo, módulos, entorno", f.get(12));
    }

    @Test
    void atTheMinimumNoDataIsLeft() {
        List<String> f = paint(80, 12);
        assertEquals(11, f.size());
        assertEquals("Xploits", f.get(0));
        assertEquals("─".repeat(80), f.get(1));
        assertEquals("─".repeat(80), f.get(8));
        assertEquals(Menu.line(ES), f.get(9));
        assertEquals("filtro: todo · juego conectado · ocultas: logo, módulos, entorno, vuelo, combate", f.get(10));
    }

    @Test
    void tooSmallSaysSoAndNothingElse() {
        List<String> f = paint(50, 10);
        assertEquals(9, f.size());
        assertEquals("ventana demasiado pequeña: 50x10, mínimo 60x12", f.get(0));
        for (int i = 1; i < 9; i++) assertEquals("", f.get(i));
    }

    @Test
    void anUnknownValueIsAQuestionMarkNeverZero() {
        List<String> f = stripColor(ScreenFrame.compose(input(110, 46, GameSnapshot.withoutPlayer(List.of(), Language.ES), ALIVE,
            List.of(), LogFilter.ALL, false, 0)));
        assertEquals("dimensión ? · jugadores cargados ? · nuestros ?", f.get(13));
        assertEquals("vida ? · armadura ? · en barra: obsidiana ? · cristales ? · telarañas ? · yunques ?", f.get(15));
    }

    @Test
    void withNoSnapshotFromTheGame() {
        List<String> f = stripColor(ScreenFrame.compose(input(110, 46, null, new Heartbeat.GameState.NoData(), List.of(),
            LogFilter.ALL, false, 0)));
        assertEquals("sin datos del juego todavía", f.get(13));
        assertEquals("filtro: todo · esperando al juego", f.get(44));
    }

    @Test
    void theLogFiltersAndColorsTheWarnings() {
        List<LogEntry.Message> ms = List.of(
            new LogEntry.Message(0, 43_200_000, "s", Level.INFO, "auto-pvp", "a"),
            new LogEntry.Message(1, 43_200_000, "s", Level.WARNING, "auto-travel", "b"));
        List<String> pvpOnly = stripColor(ScreenFrame.compose(input(110, 46, SNAPSHOT, ALIVE, ms, LogFilter.PVP, false, 0)));
        assertEquals("12:00:00  auto-pvp      a", pvpOnly.get(41));
        assertEquals("", pvpOnly.get(40));
        List<String> all = ScreenFrame.compose(input(110, 46, SNAPSHOT, ALIVE, ms, LogFilter.ALL, false, 0));
        assertTrue(all.get(41).startsWith(Ansi.color(Ansi.YELLOW)));
        assertEquals("12:00:00  auto-travel   b", Ansi.stripColor(all.get(41)));
        assertEquals("12:00:00  auto-pvp      a", Ansi.stripColor(all.get(40)));
    }

    @Test
    void aMessageWithWideCharactersDoesNotOverflowTheWindow() {
        List<LogEntry.Message> ms = List.of(new LogEntry.Message(0, 0, "s", Level.INFO, "player漢字漢字",
            "漢字".repeat(100) + "😀é"));
        List<String> f = stripColor(ScreenFrame.compose(input(60, 20, SNAPSHOT, ALIVE, ms, LogFilter.ALL, false, 0)));
        for (String row : f) assertTrue(TerminalText.width(row) <= 60, row);
    }

    @Test
    void thePauseShowsWithTheNewCount() {
        List<String> f = paint(110, 46);
        assertEquals("filtro: todo · juego conectado", f.get(44));
        List<String> paused = stripColor(ScreenFrame.compose(input(110, 46, SNAPSHOT, ALIVE, List.of(), LogFilter.ALL, true, 3)));
        assertEquals("filtro: todo · PAUSA (3 nuevas) · juego conectado", paused.get(44));
    }

    @Test
    void aGameThatDoesNotRespondShowsInRed() {
        List<String> f = ScreenFrame.compose(input(110, 46, SNAPSHOT, new Heartbeat.GameState.NotResponding(20), List.of(),
            LogFilter.ALL, false, 0));
        assertTrue(f.get(44).startsWith(Ansi.color(Ansi.RED)));
        assertEquals("filtro: todo · EL JUEGO NO RESPONDE (20 s)", Ansi.stripColor(f.get(44)));
    }

    @Test
    void theStatusLineInEnglish() {
        List<String> f = stripColor(ScreenFrame.compose(input(110, 46, SNAPSHOT, ALIVE, List.of(), LogFilter.WARNINGS, true, 3, EN)));
        assertEquals("[1] all  [2] pvp  [3] travel  [4] sweep  [5] warnings only  [6] pause  [0] exit", f.get(43));
        assertEquals("filter: warnings only · PAUSED (3 new) · game connected", f.get(44));
    }
}
