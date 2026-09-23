package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TamanoTest {
    @Test
    void laSalidaEnEspanolDeModeCon() {
        String salida = "\r\nEstado para dispositivo CON:\r\n----------------------------\r\n"
            + "    Líneas:          40\r\n    Columnas:        110\r\n    Ritmo del teclado: 31\r\n";
        assertEquals(Optional.of(new Tamano(110, 40)), Tamano.deModeCon(salida));
    }

    @Test
    void laSalidaEnInglesDeModeCon() {
        String salida = "\nStatus for device CON:\n----------------------\n    Lines:          30\n    Columns:        120\n";
        assertEquals(Optional.of(new Tamano(120, 30)), Tamano.deModeCon(salida));
    }

    @Test
    void sinRayaOSinDosNumerosNoHayTamano() {
        assertEquals(Optional.empty(), Tamano.deModeCon("Líneas: 40 Columnas: 110"));
        assertEquals(Optional.empty(), Tamano.deModeCon("CON:\n----\n    Líneas: 40\n"));
    }

    @Test
    void elMinimo() {
        assertTrue(new Tamano(60, 12).cabeElMinimo());
        assertFalse(new Tamano(59, 12).cabeElMinimo());
        assertFalse(new Tamano(60, 11).cabeElMinimo());
    }
}
