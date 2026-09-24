package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EscapeTest {
    @Test
    void escapeAndUnescapeAreInverses() {
        String odd = "a\\b\tc\nd\re;f=g,h|i";
        assertEquals("a\\\\b\\tc\\nd\\re\\;f\\=g\\,h\\|i", Escape.escape(odd));
        assertEquals(odd, Escape.unescape(Escape.escape(odd)));
    }

    @Test
    void aLoneTrailingBackslashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Escape.unescape("abc\\"));
    }

    @Test
    void anUnknownEscapeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Escape.unescape("a\\qb"));
    }

    @Test
    void splitRespectsEscapedSeparators() {
        assertEquals(List.of("a\\;b", "c", ""), Escape.split("a\\;b;c;", ';'));
    }
}
