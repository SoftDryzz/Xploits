package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowSizeTest {
    @Test
    void theSpanishOutputOfModeCon() {
        String output = "\r\nEstado para dispositivo CON:\r\n----------------------------\r\n"
            + "    Líneas:          40\r\n    Columnas:        110\r\n    Ritmo del teclado: 31\r\n";
        assertEquals(Optional.of(new WindowSize(110, 40)), WindowSize.fromModeCon(output));
    }

    @Test
    void theEnglishOutputOfModeCon() {
        String output = "\nStatus for device CON:\n----------------------\n    Lines:          30\n    Columns:        120\n";
        assertEquals(Optional.of(new WindowSize(120, 30)), WindowSize.fromModeCon(output));
    }

    @Test
    void withoutADashOrTwoNumbersThereIsNoSize() {
        assertEquals(Optional.empty(), WindowSize.fromModeCon("Líneas: 40 Columnas: 110"));
        assertEquals(Optional.empty(), WindowSize.fromModeCon("CON:\n----\n    Líneas: 40\n"));
    }

    @Test
    void theMinimum() {
        assertTrue(new WindowSize(60, 12).fitsMinimum());
        assertFalse(new WindowSize(59, 12).fitsMinimum());
        assertFalse(new WindowSize(60, 11).fitsMinimum());
    }
}
