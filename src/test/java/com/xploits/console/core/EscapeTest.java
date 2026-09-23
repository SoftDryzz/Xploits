package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EscapeTest {
    @Test
    void escaparYDesescaparSonInversos() {
        String raro = "a\\b\tc\nd\re;f=g,h|i";
        assertEquals("a\\\\b\\tc\\nd\\re\\;f\\=g\\,h\\|i", Escape.escapar(raro));
        assertEquals(raro, Escape.desescapar(Escape.escapar(raro)));
    }

    @Test
    void unaBarraSueltaAlFinalSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> Escape.desescapar("abc\\"));
    }

    @Test
    void unEscapeDesconocidoSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> Escape.desescapar("a\\qb"));
    }

    @Test
    void partirRespetaLosSeparadoresEscapados() {
        assertEquals(List.of("a\\;b", "c", ""), Escape.partir("a\\;b;c;", ';'));
    }
}
