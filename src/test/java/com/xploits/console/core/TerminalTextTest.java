package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerminalTextTest {
    @Test
    void anInjectedEscNeverReachesTheTerminal() {
        assertEquals("hello?[2Jbye", TerminalText.sanitize("hello\u001b[2Jbye"));
    }

    @Test
    void c1ControlsAreNeutralizedToo() {
        assertEquals("a?b", TerminalText.sanitize("a\u0085b"));
    }

    @Test
    void bidiMarksAreNeutralized() {
        assertEquals("a?b", TerminalText.sanitize("a‮b"));
    }

    @Test
    void minecraftColorCodesAreStripped() {
        assertEquals("Red normal", TerminalText.sanitize("§cRed§r normal"));
    }

    @Test
    void meteorTokensAreStripped() {
        assertEquals("x", TerminalText.sanitize("(highlight)x(default)"));
    }

    @Test
    void aTabIsASpaceAndACarriageReturnDisappears() {
        assertEquals("a b\nc", TerminalText.sanitize("a\tb\r\nc"));
    }

    @Test
    void widthsPerCodePoint() {
        assertEquals(5, TerminalText.width("áéíóú"));
        assertEquals(1, TerminalText.width("e\u0301"));
        assertEquals(4, TerminalText.width("漢字"));
        assertEquals(2, TerminalText.width("▀▄"));
        assertEquals(2, TerminalText.width("😀"));
    }

    @Test
    void truncateLeavesWhatFitsAlone() {
        assertEquals("abcde", TerminalText.truncate("abcde", 5));
    }

    @Test
    void truncateJustOverTheWidth() {
        assertEquals("abcd…", TerminalText.truncate("abcdef", 5));
    }

    @Test
    void truncateNeverSplitsASurrogatePair() {
        String r = TerminalText.truncate("ab😀cd", 4);
        assertEquals("ab…", r);
        assertFalse(Character.isHighSurrogate(r.charAt(r.length() - 2)));
    }

    @Test
    void truncateToOneColumnOrNone() {
        assertEquals("…", TerminalText.truncate("abc", 1));
        assertEquals("", TerminalText.truncate("abc", 0));
    }

    @Test
    void wrapByWidthAndByNewlines() {
        assertEquals(List.of("abc", "def", "gh", "xy"), TerminalText.wrap("abcdefgh\nxy", 3, 10));
    }

    @Test
    void wrapWithTooManyRowsSaysSo() {
        assertEquals(List.of("aaaaaaaaaa", "aaaa… (+1)"), TerminalText.wrap("a".repeat(25), 10, 2));
    }

    @Test
    void wrapIgnoresTheTrailingNewline() {
        assertEquals(List.of("ab"), TerminalText.wrap("ab\n", 10, 5));
    }

    @Test
    void wrappingAWideCharacterLeavesNoEmptyRow() {
        assertEquals(List.of("a", "字"), TerminalText.wrap("a字", 2, 5));
    }

    @Test
    void wrapWithASuffixWiderThanTheWindowDoesNotOverflow() {
        assertEquals(List.of("(+6)"), TerminalText.wrap("a".repeat(25), 4, 1));
    }

    @Test
    void wrapRejectsNonsensicalWidthsOrRows() {
        assertThrows(IllegalArgumentException.class, () -> TerminalText.wrap("a", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> TerminalText.wrap("a", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TerminalText.wrap("a", 5, 0));
    }
}
