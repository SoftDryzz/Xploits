package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatoTest {
    @Test
    void unFormatoBuenoSaleFormateado() {
        assertEquals(new Formato.Resultado("a b", false), Formato.aplicar("a %s", "b"));
        assertEquals(new Formato.Resultado("50%", false), Formato.aplicar("50%%"));
    }

    @Test
    void unFormatoRotoNoPierdeElMensajeYLoDice() {
        Formato.Resultado r = Formato.aplicar("50%");
        assertTrue(r.roto());
        assertTrue(r.texto().startsWith("50% [formato roto: "), r.texto());
        assertEquals(new Formato.Resultado("%d [formato roto: IllegalFormatConversionException]", true),
            Formato.aplicar("%d", "x"));
        assertEquals(new Formato.Resultado("%s [formato roto: MissingFormatArgumentException]", true),
            Formato.aplicar("%s"));
    }
}
