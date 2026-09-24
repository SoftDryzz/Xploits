package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One line of the readable history (console spec §5): date with milliseconds and offset (so it stays
 * unambiguous even when the clock changes), level, source and text; continuation lines are indented.
 */
public final class History {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx");
    private static final String INDENT = "    ";

    private History() {
    }

    /** The line for an entry, or null if it does not go to the history (snapshots and close orders). */
    public static String line(LogEntry e, ZoneId zone, Catalog texts) {
        String date = DATE.format(Instant.ofEpochMilli(e.epochMs()).atZone(zone));
        return switch (e) {
            case LogEntry.Message m -> date + "  " + pad(m.level().label(texts)) + "  " + m.source() + "  " + body(m.text());
            case LogEntry.Game g -> date + "  " + pad(texts.render(ConsoleText.HISTORY_GAME)) + "  "
                + body(reason(g.reason(), texts));
            case LogEntry.Lost l -> date + "  " + pad(Level.WARNING.label(texts)) + "  console  "
                + texts.render(ConsoleText.HISTORY_LOST, "count", l.count());
            case LogEntry.Snapshot s -> null;
            case LogEntry.Close c -> null;
        };
    }

    /** {@code start} and {@code end} are protocol words in live.log; the history says them in its language. */
    private static String reason(String reason, Catalog texts) {
        return switch (reason) {
            case "start" -> texts.render(ConsoleText.GAME_START);
            case "end" -> texts.render(ConsoleText.GAME_END);
            default -> reason;
        };
    }

    private static String pad(String label) {
        return label + " ".repeat(Math.max(0, 5 - label.length()));
    }

    private static String body(String text) {
        return TerminalText.sanitize(text).replace("\n", "\n" + INDENT);
    }
}
