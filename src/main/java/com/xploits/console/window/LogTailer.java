package com.xploits.console.window;

import com.xploits.console.core.LineReader;
import com.xploits.console.core.LogEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Follows the end of {@code live.log} while the game writes and rotates it (console spec §5).
 *
 * <p>It never leaves a file open: it opens through NIO, reads and closes on every round. A file opened
 * with {@code FileInputStream} does not share deletion on Windows and would block the game's rotation.
 */
final class LogTailer {
    private final Path liveLog;
    private final Path previousLog;
    private final LineReader reader = new LineReader();
    private String generation;
    private long offset;

    LogTailer(Path folder) {
        liveLog = folder.resolve("live.log");
        previousLog = folder.resolve("live.1.log");
    }

    /** The new complete lines since the previous round, without the header. */
    List<String> read() throws IOException {
        if (!Files.exists(liveLog)) return List.of();
        String current = generationOf(liveLog);
        if (current == null) return List.of();
        List<String> lines = new ArrayList<>();
        if (generation == null) {
            generation = current;
            offset = 0;
            reader.forget();
        } else if (LineReader.detect(offset, Files.size(liveLog), generation, current) == LineReader.Change.ROTATED) {
            // What was left of the rotated file, if it is the one being followed.
            if (Files.exists(previousLog) && generation.equals(generationOf(previousLog))) {
                lines.addAll(tail(previousLog, offset).lines());
            }
            generation = current;
            offset = 0;
            reader.forget();
        }
        Tail tail = tail(liveLog, offset);
        offset = tail.upTo();
        lines.addAll(tail.lines());
        lines.removeIf(LogEntry::isHeader);
        return lines;
    }

    private record Tail(List<String> lines, long upTo) {
    }

    private Tail tail(Path file, long from) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() <= from) return new Tail(List.of(), from);
            channel.position(from);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            List<String> lines = new ArrayList<>();
            long position = from;
            int n;
            while ((n = channel.read(buffer)) > 0) {
                lines.addAll(reader.feed(buffer.array(), 0, n));
                position += n;
                buffer.clear();
            }
            return new Tail(lines, position);
        }
    }

    /** The header's generation, or null if the first line is not whole yet. Throws if it is from another version. */
    private static String generationOf(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(256);
            int n = channel.read(buffer);
            if (n <= 0) return null;
            String start = new String(buffer.array(), 0, n, StandardCharsets.UTF_8);
            int newline = start.indexOf('\n');
            if (newline < 0) return null;
            return LogEntry.generationOf(start.substring(0, newline));
        }
    }
}
