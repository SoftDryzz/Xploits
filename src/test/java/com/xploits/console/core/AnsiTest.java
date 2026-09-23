package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiTest {
    @Test
    void secuenciasExactas() {
        assertEquals("\u001b[3;7H", Ansi.irA(3, 7));
        assertEquals("\u001b[8;46;110t", Ansi.tamano(46, 110));
        assertEquals("\u001b[46;46r", Ansi.region(46, 46));
        assertEquals("\u001b[38;5;45m", Ansi.color(45));
    }

    @Test
    void unFotogramaExactoQueRestauraElCursor() {
        assertEquals("\u001b[?2026h\u001b7\u001b[1;1Ha\u001b[0m\u001b[K\u001b[2;1Hb\u001b[0m\u001b[K\u001b8\u001b[?2026l",
            Ansi.fotograma(List.of("a", "b"), 3, false));
    }

    @Test
    void trasUnEnterSeReponeElPromptEnVezDeRestaurar() {
        String f = Ansi.fotograma(List.of("a"), 5, true);
        assertTrue(f.contains("\u001b[5;1H> \u001b[K"));
        assertFalse(f.contains(Ansi.RESTAURAR));
    }

    @Test
    void unFotogramaNuncaBorraLaPantallaEntera() {
        assertFalse(Ansi.fotograma(List.of("a", "b", "c"), 4, false).contains(Ansi.BORRAR_PANTALLA));
        assertFalse(Ansi.fotograma(List.of("a"), 2, true).contains(Ansi.BORRAR_PANTALLA));
    }

    @Test
    void sinColorQuitaSoloElColor() {
        assertEquals("hola", Ansi.sinColor("\u001b[38;5;45mhola\u001b[0m"));
    }

    @Test
    void elTituloSeSanea() {
        assertEquals("\u001b]0;a?b\u0007", Ansi.titulo("a\u001bb"));
    }
}
