package com.xploits.console.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The escaping of the console's files (console spec §5). Every character that acts as a separator at
 * some level is escaped: tab (fields), {@code ;} and {@code =} (snapshot keys), {@code ,} and
 * {@code |} (module list), and line breaks.
 */
public final class Escape {
    private Escape() {
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case ';' -> sb.append("\\;");
                case '=' -> sb.append("\\=");
                case ',' -> sb.append("\\,");
                case '|' -> sb.append("\\|");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) throw new IllegalArgumentException("a lone backslash at the end of the field");
            char next = s.charAt(++i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case ';', '=', ',', '|' -> sb.append(next);
                default -> throw new IllegalArgumentException("unknown escape: \\" + next);
            }
        }
        return sb.toString();
    }

    /** Splits on a separator that is not escaped. The pieces stay escaped. */
    public static List<String> split(String s, char separator) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                current.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == separator) {
                pieces.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        pieces.add(current.toString());
        return pieces;
    }
}
