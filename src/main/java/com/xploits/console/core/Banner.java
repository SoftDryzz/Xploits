package com.xploits.console.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Xploits logo (console spec §15), CustomCLI's compact cut ({@code art/xploits-compacto.txt}). It is
 * drawn at full size or not at all: scaled down, the strokes fall below the rasterizing threshold and the
 * letters break up (verified on screenshots).
 */
public final class Banner {
    public static final String RESOURCE = "/xploits/console/logo.ans";
    public static final int ROWS = 12;
    public static final int MAX_WIDTH = 100;
    public static final int MIN_COLUMNS = 100;
    public static final int MIN_ROWS = 40;
    public static final String TEXT = "Xploits";
    private static final Pattern COLOR = Pattern.compile("\u001b\\[[0-9;]*m");

    private Banner() {
    }

    public static List<String> load() throws IOException {
        try (InputStream in = Banner.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("resource " + RESOURCE + " is missing from the jar");
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            validate(lines);
            return lines;
        }
    }

    /** Throws if the logo is not what is expected: a broken logo is never drawn as if it were the good one. */
    public static void validate(List<String> art) {
        if (art.size() != ROWS) {
            throw new IllegalArgumentException("the logo must have " + ROWS + " rows and has " + art.size());
        }
        for (int i = 0; i < art.size(); i++) {
            String line = art.get(i);
            String withoutColor = COLOR.matcher(line).replaceAll("");
            if (withoutColor.indexOf('\u001b') >= 0) {
                throw new IllegalArgumentException("row " + (i + 1) + " of the logo carries an escape that is not a color");
            }
            if (!line.endsWith(Ansi.RESET)) {
                throw new IllegalArgumentException("row " + (i + 1) + " of the logo does not end in ESC[0m: the color would leak into the next one");
            }
            int width = TerminalText.width(withoutColor);
            if (width > MAX_WIDTH) {
                throw new IllegalArgumentException("row " + (i + 1) + " of the logo is " + width + " columns wide and the maximum is " + MAX_WIDTH);
            }
        }
    }

    public static int visibleWidth(String line) {
        return TerminalText.width(Ansi.stripColor(line));
    }

    public static boolean fits(int cols, int rows) {
        return cols >= MIN_COLUMNS && rows >= MIN_ROWS;
    }

    /** The logo if the window has room for it; otherwise the name on one row, in the logo's purple. */
    public static List<String> choose(List<String> art, int cols, int rows) {
        if (fits(cols, rows)) return art;
        return List.of(Ansi.color(Ansi.PURPLE) + TEXT + Ansi.RESET);
    }
}
