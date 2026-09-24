package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlyphsTest {
    @Test
    void eachSetHasItsCharacters() {
        assertEquals("───", Glyphs.UNICODE.line(3));
        assertEquals("---", Glyphs.ASCII.line(3));
        assertEquals("●", Glyphs.UNICODE.active());
        assertEquals("o", Glyphs.ASCII.inactive());
        assertEquals("", Glyphs.UNICODE.line(0));
    }
}
