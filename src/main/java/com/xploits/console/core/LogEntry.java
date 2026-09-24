package com.xploits.console.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The lines of {@code live.log} (console spec §5): one per entry, fields separated by tabs and each
 * field escaped. {@code seq} grows one by one within a game session: a jump is a lost entry.
 *
 * <p>The record letters ({@code R S J F P}) are opaque protocol letters and stay as they are
 * (code-in-English design §6).
 */
public sealed interface LogEntry permits LogEntry.Message, LogEntry.Snapshot, LogEntry.Game, LogEntry.Close, LogEntry.Lost {
    int VERSION = 3;
    String HEADER_PREFIX = "#xploits-console";

    long seq();

    long epochMs();

    String session();

    /** A message from a module or from the command. */
    record Message(long seq, long epochMs, String session, Level level, String source, String text) implements LogEntry {
    }

    /** A snapshot for the header. It is also the game's heartbeat. */
    record Snapshot(long seq, long epochMs, String session, GameSnapshot snapshot) implements LogEntry {
    }

    /** The game starts writing ({@code start}) or shuts down ({@code end}). */
    record Game(long seq, long epochMs, String session, String reason) implements LogEntry {
    }

    /** The order to close, only for the window of that launch. */
    record Close(long seq, long epochMs, String session, String launchId, String reason) implements LogEntry {
    }

    /** Messages were lost because the queue was full. */
    record Lost(long seq, long epochMs, String session, long count) implements LogEntry {
    }

    default String encode() {
        List<String> fields = switch (this) {
            case Message m -> List.of("R", Long.toString(m.seq()), Long.toString(m.epochMs()), m.session(),
                String.valueOf(m.level().code()), m.source(), m.text());
            case Snapshot s -> List.of("S", Long.toString(s.seq()), Long.toString(s.epochMs()), s.session(),
                s.snapshot().encode());
            case Game g -> List.of("J", Long.toString(g.seq()), Long.toString(g.epochMs()), g.session(), g.reason());
            case Close c -> List.of("F", Long.toString(c.seq()), Long.toString(c.epochMs()), c.session(),
                c.launchId(), c.reason());
            case Lost l -> List.of("P", Long.toString(l.seq()), Long.toString(l.epochMs()), l.session(),
                Long.toString(l.count()));
        };
        return fields.stream().map(Escape::escape).collect(Collectors.joining("\t"));
    }

    static LogEntry decode(String line) {
        List<String> c = Escape.split(line, '\t').stream().map(Escape::unescape).toList();
        String type = c.get(0);
        int expected = switch (type) {
            case "R" -> 7;
            case "F" -> 6;
            case "S", "J", "P" -> 5;
            default -> throw new IllegalArgumentException("unknown entry type: " + type);
        };
        if (c.size() != expected) {
            throw new IllegalArgumentException("a " + type + " entry has " + expected + " fields and this one has " + c.size());
        }
        long seq = parseNumber(c.get(1), "seq");
        long epochMs = parseNumber(c.get(2), "epochMs");
        String session = c.get(3);
        return switch (type) {
            case "R" -> {
                if (c.get(4).length() != 1) throw new IllegalArgumentException("malformed level: " + c.get(4));
                yield new Message(seq, epochMs, session, Level.fromCode(c.get(4).charAt(0)), c.get(5), c.get(6));
            }
            case "S" -> new Snapshot(seq, epochMs, session, GameSnapshot.decode(c.get(4)));
            case "J" -> new Game(seq, epochMs, session, c.get(4));
            case "F" -> new Close(seq, epochMs, session, c.get(4), c.get(5));
            case "P" -> new Lost(seq, epochMs, session, parseNumber(c.get(4), "count"));
            default -> throw new AssertionError(type);
        };
    }

    private static long parseNumber(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("field " + field + " is not a number: " + value, e);
        }
    }

    static String header(String generation) {
        return HEADER_PREFIX + "\t" + VERSION + "\t" + generation;
    }

    static boolean isHeader(String line) {
        return line.startsWith(HEADER_PREFIX);
    }

    /** The generation of a header that is ours and of this version; throws if it is not. */
    static String generationOf(String line) {
        String[] c = line.split("\t", -1);
        if (c.length != 3 || !c[0].equals(HEADER_PREFIX)) {
            throw new IllegalArgumentException("not the header of a console log");
        }
        if (!c[1].equals(Integer.toString(VERSION))) {
            throw new IllegalArgumentException("console log in version " + c[1] + ": this window only understands " + VERSION);
        }
        if (c[2].isEmpty()) throw new IllegalArgumentException("header without a generation");
        return c[2];
    }
}
