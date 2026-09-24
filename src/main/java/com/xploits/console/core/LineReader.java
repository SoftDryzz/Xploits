package com.xploits.console.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the bytes read, bit by bit, from the end of {@code live.log} into complete lines.
 *
 * <p>Decoding is done <b>per line</b>, never per chunk: a read can cut a multi-byte character in half
 * ({@code é}, {@code ▀}), and decoding the chunk would turn it into garbage.
 */
public final class LineReader {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public List<String> feed(byte[] bytes) {
        return feed(bytes, 0, bytes.length);
    }

    public List<String> feed(byte[] bytes, int from, int to) {
        List<String> lines = new ArrayList<>();
        for (int i = from; i < to; i++) {
            byte b = bytes[i];
            if (b == '\n') {
                lines.add(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            } else {
                pending.write(b);
            }
        }
        return lines;
    }

    /** Whether a line has been started and not finished. */
    public boolean hasPartialLine() {
        return pending.size() > 0;
    }

    /** Drops what is pending: used when moving from a rotated file to the new one. */
    public void forget() {
        pending.reset();
    }

    public enum Change { CONTINUES, ROTATED }

    /**
     * Whether the file being followed is the same one. It has rotated if its header is from another
     * generation or if it is shorter than what has already been read.
     */
    public static Change detect(long read, long size, String followedGeneration, String currentGeneration) {
        if (currentGeneration != null && !currentGeneration.equals(followedGeneration)) return Change.ROTATED;
        if (size < read) return Change.ROTATED;
        return Change.CONTINUES;
    }
}
