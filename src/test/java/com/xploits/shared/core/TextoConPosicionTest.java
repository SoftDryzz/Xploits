package com.xploits.shared.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextoConPosicionTest {
    @Test
    void igualPoneElMismoTextoEnLosDosSitios() {
        TextoConPosicion t = TextoConPosicion.igual("Pasada 2 de 7.");
        assertEquals("Pasada 2 de 7.", t.chat());
        assertEquals("Pasada 2 de 7.", t.registro());
    }

    @Test
    void ningunoDeLosDosPuedeSerNulo() {
        assertThrows(NullPointerException.class, () -> new TextoConPosicion(null, "x"));
        assertThrows(NullPointerException.class, () -> new TextoConPosicion("x", null));
    }
}
