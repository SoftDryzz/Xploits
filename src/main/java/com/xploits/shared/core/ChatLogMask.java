package com.xploits.shared.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides coordinates in chat lines on their way to Minecraft's {@code latest.log}. The chat on
 * screen is untouched; only the logged copy is masked. Every number of a coordinate-shaped group
 * becomes {@code ***}, the rest of the line stays readable.
 *
 * <p>Coordinate shapes: two or three numbers in a row whose first and last have three or more
 * digits ({@code -1234, 64, 5678}, {@code goal 1234 5678}); an axis name followed by {@code =} or
 * {@code :} ({@code x=12}, {@code Z: -99}); and {@code @x,y,z}. A lone number is never masked.
 */
public final class ChatLogMask {
    public static final String HIDDEN = "***";

    /** The {@code hide-coordinates-in-log} setting. Display names are Meteor save keys: never change them. */
    public enum Mode {
        OFF("Off"),
        BARITONE("Baritone"),
        ALL("All"),
        ALL_BUT_BARITONE("All but Baritone");

        private final String display;

        Mode(String display) {
            this.display = display;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final String WIDE = "-?\\d{3,}(?:\\.\\d+)?";
    private static final String SEP = "(?:,\\s*|\\s+)";
    private static final Pattern AXIS = Pattern.compile("(?i)(?<![a-z])([xyz]\\s*[=:]\\s*)(" + NUMBER + ")");
    private static final Pattern AT = Pattern.compile("@" + NUMBER + "," + NUMBER + "," + NUMBER);
    private static final Pattern GROUP = Pattern.compile(
        "(?<![\\w.])" + WIDE + "(?:" + SEP + NUMBER + ")?" + SEP + WIDE + "(?!\\w)(?!\\.\\d)");
    private static final Pattern ANY_NUMBER = Pattern.compile(NUMBER);

    private ChatLogMask() {
    }

    public static String apply(Mode mode, String line) {
        if (line == null || line.isEmpty()) return line;
        boolean baritone = line.stripLeading().startsWith("[Baritone]");
        boolean masked = switch (mode) {
            case OFF -> false;
            case BARITONE -> baritone;
            case ALL -> true;
            case ALL_BUT_BARITONE -> !baritone;
        };
        return masked ? mask(line) : line;
    }

    public static String mask(String line) {
        String s = AXIS.matcher(line).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + HIDDEN));
        s = AT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(hideNumbers(m.group())));
        return GROUP.matcher(s).replaceAll(m -> Matcher.quoteReplacement(hideNumbers(m.group())));
    }

    private static String hideNumbers(String group) {
        return ANY_NUMBER.matcher(group).replaceAll(HIDDEN);
    }
}
