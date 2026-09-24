package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineReaderTest {
    @Test
    void aCharacterSplitAcrossTwoReadsArrivesWhole() {
        LineReader reader = new LineReader();
        assertEquals(List.of(), reader.feed(new byte[]{(byte) 0xC3}));
        assertEquals(List.of("é"), reader.feed(new byte[]{(byte) 0xA9, '\n'}));
    }

    @Test
    void withoutANewlineThereIsNoLineYet() {
        LineReader reader = new LineReader();
        assertEquals(List.of(), reader.feed("abc".getBytes(StandardCharsets.UTF_8)));
        assertTrue(reader.hasPartialLine());
        assertEquals(List.of("abc"), reader.feed("\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void severalLinesAtOnce() {
        LineReader reader = new LineReader();
        assertEquals(List.of("a", "b"), reader.feed("a\nb\nc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("c"), reader.feed("\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void forgetDropsWhatIsPending() {
        LineReader reader = new LineReader();
        reader.feed("half".getBytes(StandardCharsets.UTF_8));
        reader.forget();
        assertEquals(List.of("x"), reader.feed("x\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void detectsTheRotation() {
        assertEquals(LineReader.Change.CONTINUES, LineReader.detect(100, 200, "g1", "g1"));
        assertEquals(LineReader.Change.ROTATED, LineReader.detect(100, 200, "g1", "g2"));
        assertEquals(LineReader.Change.ROTATED, LineReader.detect(300, 200, "g1", "g1"));
        assertEquals(LineReader.Change.CONTINUES, LineReader.detect(100, 200, "g1", null));
    }
}
