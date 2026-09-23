package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlifosTest {
    @Test
    void cadaJuegoTieneSusCaracteres() {
        assertEquals("───", Glifos.UNICODE.linea(3));
        assertEquals("---", Glifos.ASCII.linea(3));
        assertEquals("●", Glifos.UNICODE.activo());
        assertEquals("o", Glifos.ASCII.inactivo());
        assertEquals("", Glifos.UNICODE.linea(0));
    }
}
