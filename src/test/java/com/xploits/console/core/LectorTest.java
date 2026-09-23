package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LectorTest {
    @Test
    void unaLetraPartidaEntreDosLecturasLlegaEntera() {
        Lector lector = new Lector();
        assertEquals(List.of(), lector.alimentar(new byte[]{(byte) 0xC3}));
        assertEquals(List.of("é"), lector.alimentar(new byte[]{(byte) 0xA9, '\n'}));
    }

    @Test
    void sinSaltoNoHayLineaTodavia() {
        Lector lector = new Lector();
        assertEquals(List.of(), lector.alimentar("abc".getBytes(StandardCharsets.UTF_8)));
        assertTrue(lector.aMedias());
        assertEquals(List.of("abc"), lector.alimentar("\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void variasLineasDeGolpe() {
        Lector lector = new Lector();
        assertEquals(List.of("a", "b"), lector.alimentar("a\nb\nc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("c"), lector.alimentar("\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void olvidarTiraLoPendiente() {
        Lector lector = new Lector();
        lector.alimentar("medio".getBytes(StandardCharsets.UTF_8));
        lector.olvidar();
        assertEquals(List.of("x"), lector.alimentar("x\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void detectarLaRotacion() {
        assertEquals(Lector.Cambio.SIGUE, Lector.detectar(100, 200, "g1", "g1"));
        assertEquals(Lector.Cambio.ROTADO, Lector.detectar(100, 200, "g1", "g2"));
        assertEquals(Lector.Cambio.ROTADO, Lector.detectar(300, 200, "g1", "g1"));
        assertEquals(Lector.Cambio.SIGUE, Lector.detectar(100, 200, "g1", null));
    }
}
