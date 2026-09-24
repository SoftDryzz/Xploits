package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeartbeatTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void boundariesAtThreeAndFifteenSeconds() {
        assertEquals(new Heartbeat.GameState.Alive(), Heartbeat.evaluate(1000L, 3999, true, false));
        assertEquals(new Heartbeat.GameState.Slow(3), Heartbeat.evaluate(1000L, 4000, true, false));
        assertEquals(new Heartbeat.GameState.Slow(15), Heartbeat.evaluate(1000L, 16000, true, false));
        assertEquals(new Heartbeat.GameState.NotResponding(15), Heartbeat.evaluate(1000L, 16001, true, false));
    }

    @Test
    void withNoHeartbeatYetItWaits() {
        assertEquals(new Heartbeat.GameState.NoData(), Heartbeat.evaluate(null, 5000, true, false));
    }

    @Test
    void aDeadPidBeatsAFreshHeartbeat() {
        assertEquals(new Heartbeat.GameState.Closed(), Heartbeat.evaluate(1000L, 1500, false, true));
        assertEquals(new Heartbeat.GameState.ClosedWithoutGoodbye(), Heartbeat.evaluate(1000L, 1500, false, false));
    }

    @Test
    void eachStateIsNamedAndTheBadOnesAreColored() {
        assertEquals("juego conectado", Heartbeat.text(new Heartbeat.GameState.Alive(), ES));
        assertEquals("esperando al juego", Heartbeat.text(new Heartbeat.GameState.NoData(), ES));
        assertEquals("sin latido del juego hace 7 s", Heartbeat.text(new Heartbeat.GameState.Slow(7), ES));
        assertEquals("EL JUEGO NO RESPONDE (20 s)", Heartbeat.text(new Heartbeat.GameState.NotResponding(20), ES));
        assertEquals("juego cerrado", Heartbeat.text(new Heartbeat.GameState.Closed(), ES));
        assertEquals("EL JUEGO TERMINÓ SIN DESPEDIRSE", Heartbeat.text(new Heartbeat.GameState.ClosedWithoutGoodbye(), ES));
        assertEquals("no heartbeat from the game for 7 s", Heartbeat.text(new Heartbeat.GameState.Slow(7), EN));
        assertEquals(0, Heartbeat.color(new Heartbeat.GameState.Alive()));
        assertEquals(Ansi.YELLOW, Heartbeat.color(new Heartbeat.GameState.Slow(7)));
        assertEquals(Ansi.RED, Heartbeat.color(new Heartbeat.GameState.NotResponding(20)));
        assertEquals(Ansi.RED, Heartbeat.color(new Heartbeat.GameState.ClosedWithoutGoodbye()));
    }
}
