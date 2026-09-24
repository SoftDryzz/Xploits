package com.xploits.console.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The window's size in columns and rows (console spec §6). */
public record WindowSize(int cols, int rows) {
    public static final WindowSize REQUESTED = new WindowSize(110, 46);
    public static final WindowSize MINIMUM = new WindowSize(60, 12);
    private static final Pattern NUMBER = Pattern.compile("\\d{1,5}");

    public boolean fitsMinimum() {
        return cols >= MINIMUM.cols && rows >= MINIMUM.rows;
    }

    /**
     * Reads the output of {@code mode con}, which comes translated into the Windows language: the first
     * two numbers after the line of dashes are the rows and the columns (verified in Spanish).
     */
    public static Optional<WindowSize> fromModeCon(String output) {
        int dashes = output.indexOf("---");
        if (dashes < 0) return Optional.empty();
        int dashesEnd = output.indexOf('\n', dashes);
        if (dashesEnd < 0) return Optional.empty();
        Matcher m = NUMBER.matcher(output.substring(dashesEnd));
        if (!m.find()) return Optional.empty();
        int rows = Integer.parseInt(m.group());
        if (!m.find()) return Optional.empty();
        int cols = Integer.parseInt(m.group());
        if (rows <= 0 || cols <= 0) return Optional.empty();
        return Optional.of(new WindowSize(cols, rows));
    }
}
