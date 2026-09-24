package com.xploits.console.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * How much the console leaves on disk (console spec §5): {@code live.log} rotates at 1 MiB; the
 * history keeps 30 days or 64 MiB, whichever comes first, and today's file is never deleted.
 */
public final class LogRotation {
    public static final long LIVE_LOG_LIMIT = 1L << 20;
    public static final int DAYS = 30;
    public static final long HISTORY_LIMIT = 64L << 20;

    private LogRotation() {
    }

    public static boolean shouldRotateLiveLog(long currentSize, long newBytes) {
        return currentSize + newBytes > LIVE_LOG_LIMIT;
    }

    public record LogFile(String name, long size, LocalDate date) {
    }

    /** The date of a history file, or empty if the name is not {@code YYYY-MM-DD.log}. */
    public static Optional<LocalDate> dateOf(String name) {
        if (!name.endsWith(".log")) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(name.substring(0, name.length() - ".log".length())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String fileNameFor(LocalDate day) {
        return day + ".log";
    }

    /** Which history files to delete, oldest first. */
    public static List<String> toDelete(List<LogFile> files, LocalDate today) {
        LocalDate firstKept = today.minusDays(DAYS - 1);
        List<String> delete = new ArrayList<>();
        List<LogFile> kept = new ArrayList<>();
        for (LogFile f : files.stream().sorted(Comparator.comparing(LogFile::date)).toList()) {
            if (f.date().isBefore(firstKept)) delete.add(f.name());
            else kept.add(f);
        }
        long total = kept.stream().mapToLong(LogFile::size).sum();
        for (LogFile f : kept) {
            if (total <= HISTORY_LIMIT || !f.date().isBefore(today)) break;
            delete.add(f.name());
            total -= f.size();
        }
        return delete;
    }
}
