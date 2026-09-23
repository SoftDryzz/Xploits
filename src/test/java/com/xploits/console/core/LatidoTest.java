package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatidoTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void fronterasDeTresYQuinceSegundos() {
        assertEquals(new Latido.EstadoJuego.Vivo(), Latido.evaluar(1000L, 3999, true, false));
        assertEquals(new Latido.EstadoJuego.Lento(3), Latido.evaluar(1000L, 4000, true, false));
        assertEquals(new Latido.EstadoJuego.Lento(15), Latido.evaluar(1000L, 16000, true, false));
        assertEquals(new Latido.EstadoJuego.NoResponde(15), Latido.evaluar(1000L, 16001, true, false));
    }

    @Test
    void sinNingunLatidoTodaviaSeEspera() {
        assertEquals(new Latido.EstadoJuego.SinDatos(), Latido.evaluar(null, 5000, true, false));
    }

    @Test
    void unPidMuertoGanaAUnLatidoFresco() {
        assertEquals(new Latido.EstadoJuego.Cerrado(), Latido.evaluar(1000L, 1500, false, true));
        assertEquals(new Latido.EstadoJuego.CerradoSinDespedirse(), Latido.evaluar(1000L, 1500, false, false));
    }

    @Test
    void cadaEstadoSeDiceYLosMalosVanEnColor() {
        assertEquals("juego conectado", Latido.texto(new Latido.EstadoJuego.Vivo(), ES));
        assertEquals("esperando al juego", Latido.texto(new Latido.EstadoJuego.SinDatos(), ES));
        assertEquals("sin latido del juego hace 7 s", Latido.texto(new Latido.EstadoJuego.Lento(7), ES));
        assertEquals("EL JUEGO NO RESPONDE (20 s)", Latido.texto(new Latido.EstadoJuego.NoResponde(20), ES));
        assertEquals("juego cerrado", Latido.texto(new Latido.EstadoJuego.Cerrado(), ES));
        assertEquals("EL JUEGO TERMINÓ SIN DESPEDIRSE", Latido.texto(new Latido.EstadoJuego.CerradoSinDespedirse(), ES));
        assertEquals("no heartbeat from the game for 7 s", Latido.texto(new Latido.EstadoJuego.Lento(7), EN));
        assertEquals(0, Latido.color(new Latido.EstadoJuego.Vivo()));
        assertEquals(Ansi.AMARILLO, Latido.color(new Latido.EstadoJuego.Lento(7)));
        assertEquals(Ansi.ROJO, Latido.color(new Latido.EstadoJuego.NoResponde(20)));
        assertEquals(Ansi.ROJO, Latido.color(new Latido.EstadoJuego.CerradoSinDespedirse()));
    }
}
