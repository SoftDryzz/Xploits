package com.xploits.console.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What is done to a text before drawing it in a terminal (console spec §6).
 *
 * <p>The text comes from outside more than it seems: player names, kitbot whispers, exception
 * messages. An ESC that reaches the terminal as is, is an escape injection: it can clear the screen,
 * move the cursor or change the title. That is why everything is sanitized.
 */
public final class TerminalText {
    private static final String[] METEOR_TOKENS = {"(highlight)", "(default)"};

    private TerminalText() {
    }

    /**
     * Strips Minecraft's {@code §} codes with their letter and Meteor's tokens; turns the tab into a
     * space and drops the carriage return; replaces with {@code ?} the C0 controls (except the line
     * break), DEL, the C1 controls and the bidi marks. The line break is kept.
     */
    public static String sanitize(String s) {
        String withoutTokens = s;
        for (String token : METEOR_TOKENS) withoutTokens = withoutTokens.replace(token, "");
        StringBuilder sb = new StringBuilder(withoutTokens.length());
        int i = 0;
        while (i < withoutTokens.length()) {
            int cp = withoutTokens.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') {
                if (i < withoutTokens.length()) i += Character.charCount(withoutTokens.codePointAt(i));
                continue;
            }
            if (cp == '\n') {
                sb.append('\n');
                continue;
            }
            if (cp == '\t') {
                sb.append(' ');
                continue;
            }
            if (cp == '\r') continue;
            if (isControl(cp) || isBidi(cp)) {
                sb.append('?');
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static boolean isControl(int cp) {
        return cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F);
    }

    private static boolean isBidi(int cp) {
        return cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }

    /** Terminal columns a code point takes: 0 for combining marks, 2 for wide ones, 1 for the rest. */
    public static int widthOf(int cp) {
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) return 0;
        return isWide(cp) ? 2 : 1;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
            || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
            || (cp >= 0xAC00 && cp <= 0xD7A3)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE30 && cp <= 0xFE4F)
            || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6)
            || (cp >= 0x1F300 && cp <= 0x1F64F)
            || (cp >= 0x1F900 && cp <= 0x1F9FF)
            || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    /** Columns the whole text takes. It does not understand escapes: strip them first. */
    public static int width(String s) {
        int total = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            total += widthOf(cp);
            i += Character.charCount(cp);
        }
        return total;
    }

    /** The text in {@code cols} columns at most; if it had to be cut, it ends in {@code …}. */
    public static String truncate(String s, int cols) {
        if (cols <= 0) return "";
        if (width(s) <= cols) return s;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int w = widthOf(cp);
            if (used + w > cols - 1) break;
            sb.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }

    /**
     * Splits the text into rows of {@code cols} columns at most, at line breaks and by width. If there
     * are more than {@code maxRows}, the first ones are shown and the last ends in {@code (+N)}, with N
     * the rows not shown. If the suffix leaves no room for text, the last row is the truncated suffix.
     */
    public static List<String> wrap(String s, int cols, int maxRows) {
        if (cols < 2) throw new IllegalArgumentException("cannot wrap in " + cols + " columns: a wide character needs 2");
        if (maxRows <= 0) throw new IllegalArgumentException("cannot wrap in " + maxRows + " rows");
        String body = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        List<String> rows = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            int used = 0;
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                int w = widthOf(cp);
                if (used > 0 && used + w > cols) {
                    rows.add(current.toString());
                    current.setLength(0);
                    used = 0;
                }
                current.appendCodePoint(cp);
                used += w;
                i += Character.charCount(cp);
            }
            rows.add(current.toString());
        }
        if (rows.size() <= maxRows) return rows;
        String suffix = " (+" + (rows.size() - maxRows) + ")";
        List<String> visible = new ArrayList<>(rows.subList(0, maxRows));
        String last = visible.get(maxRows - 1);
        int suffixWidth = width(suffix);
        if (cols - suffixWidth < 1) {
            visible.set(maxRows - 1, truncate(suffix.strip(), cols));
        } else {
            visible.set(maxRows - 1, truncate(last, cols - suffixWidth) + suffix);
        }
        return visible;
    }
}
