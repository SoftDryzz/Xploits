package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatidoTest {
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
        assertEquals("juego conectado", Latido.describir(new Latido.EstadoJuego.Vivo()));
        assertEquals("esperando al juego", Latido.describir(new Latido.EstadoJuego.SinDatos()));
        assertEquals("sin latido del juego hace 7 s", Latido.describir(new Latido.EstadoJuego.Lento(7)));
        assertEquals("EL JUEGO NO RESPONDE (20 s)", Latido.describir(new Latido.EstadoJuego.NoResponde(20)));
        assertEquals("juego cerrado", Latido.describir(new Latido.EstadoJuego.Cerrado()));
        assertEquals("EL JUEGO TERMINÓ SIN DESPEDIRSE", Latido.describir(new Latido.EstadoJuego.CerradoSinDespedirse()));
        assertEquals(0, Latido.color(new Latido.EstadoJuego.Vivo()));
        assertEquals(Ansi.AMARILLO, Latido.color(new Latido.EstadoJuego.Lento(7)));
        assertEquals(Ansi.ROJO, Latido.color(new Latido.EstadoJuego.NoResponde(20)));
        assertEquals(Ansi.ROJO, Latido.color(new Latido.EstadoJuego.CerradoSinDespedirse()));
    }
}
