package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiTest {
    @Test
    void exactSequences() {
        assertEquals("\u001b[3;7H", Ansi.moveTo(3, 7));
        assertEquals("\u001b[8;46;110t", Ansi.resize(46, 110));
        assertEquals("\u001b[46;46r", Ansi.region(46, 46));
        assertEquals("\u001b[38;5;45m", Ansi.color(45));
    }

    @Test
    void anExactFrameThatRestoresTheCursor() {
        assertEquals("\u001b[?2026h\u001b7\u001b[1;1Ha\u001b[0m\u001b[K\u001b[2;1Hb\u001b[0m\u001b[K\u001b8\u001b[?2026l",
            Ansi.frame(List.of("a", "b"), 3, false));
    }

    @Test
    void afterEnterThePromptIsRedrawnInsteadOfRestored() {
        String f = Ansi.frame(List.of("a"), 5, true);
        assertTrue(f.contains("\u001b[5;1H> \u001b[K"));
        assertFalse(f.contains(Ansi.RESTORE_CURSOR));
    }

    @Test
    void aFrameNeverClearsTheWholeScreen() {
        assertFalse(Ansi.frame(List.of("a", "b", "c"), 4, false).contains(Ansi.CLEAR_SCREEN));
        assertFalse(Ansi.frame(List.of("a"), 2, true).contains(Ansi.CLEAR_SCREEN));
    }

    @Test
    void stripColorRemovesOnlyTheColor() {
        assertEquals("hola", Ansi.stripColor("\u001b[38;5;45mhola\u001b[0m"));
    }

    @Test
    void theTitleIsSanitized() {
        assertEquals("\u001b]0;a?b\u0007", Ansi.title("a\u001bb"));
    }
}
