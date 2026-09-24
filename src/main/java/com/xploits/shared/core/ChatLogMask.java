package com.xploits.shared.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides coordinates in lines on their way to Minecraft's {@code latest.log}: the logged copy of
 * each chat line, and what mods print to standard output (Baritone's region files). The chat on
 * screen is untouched. Every number of a coordinate-shaped group becomes {@code ***}; the rest of
 * the line stays readable.
 *
 * <p>Coordinate shapes:
 * <ul>
 *   <li>two or three numbers after a word that introduces a place ({@code at}, {@code to},
 *       {@code goal}, {@code goto}, {@code en}, {@code hacia}, {@code hasta}), whatever their size —
 *       on a highway one axis is small;</li>
 *   <li>two or three numbers in a row whose first and last have three or more digits;</li>
 *   <li>an axis name followed by {@code =} or {@code :} ({@code x=12}, {@code Z: -99});</li>
 *   <li>{@code @x,y,z} and Baritone's {@code region x,z}.</li>
 * </ul>
 * A lone number is never masked. Minecraft logs a newline as the two characters {@code \n}; that
 * sequence counts as a boundary, so it does not shield what follows it.
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
    /** Start of line, a logged {@code \n}, or anything that is not part of a word or a number. */
    private static final String EDGE = "(?:(?<=\\\\n)|(?<![\\w.]))";

    private static final Pattern AXIS =
        Pattern.compile("(?i)(?:(?<=\\\\n)|(?<![a-z]))([xyz]\\s*[=:]\\s*)(" + NUMBER + ")");
    private static final Pattern AT = Pattern.compile("@" + NUMBER + "," + NUMBER + "," + NUMBER);
    private static final Pattern REGION = Pattern.compile("(?i)(region\\s+)(-?\\d+,-?\\d+)");
    private static final Pattern INTRODUCED = Pattern.compile(
        "(?i)(?<![\\p{L}])((?:at|to|goal|goto|en|hacia|hasta)\\s+)(" + NUMBER + "(?:" + SEP + NUMBER + "){1,2})(?!\\w)");
    private static final Pattern GROUP = Pattern.compile(
        EDGE + WIDE + "(?:" + SEP + NUMBER + ")?" + SEP + WIDE + "(?!\\w)");
    private static final Pattern ANY_NUMBER = Pattern.compile(NUMBER);
    private static final Pattern BARITONE_STDOUT = Pattern.compile("(?i)^\\s*(?:saving|loading) region\\b");

    private ChatLogMask() {
    }

    /** A chat line; Baritone's are the ones that start with {@code [Baritone]}. */
    public static String apply(Mode mode, String line) {
        if (line == null || line.isEmpty()) return line;
        return chosen(mode, line.stripLeading().startsWith("[Baritone]")) ? mask(line) : line;
    }

    /** A line printed to standard output; Baritone's are its region-file messages. */
    public static String applyStdout(Mode mode, String line) {
        if (line == null || line.isEmpty()) return line;
        return chosen(mode, BARITONE_STDOUT.matcher(line).find()) ? mask(line) : line;
    }

    public static String mask(String line) {
        String s = AXIS.matcher(line).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + HIDDEN));
        s = AT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(hideNumbers(m.group())));
        s = REGION.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + hideNumbers(m.group(2))));
        s = INTRODUCED.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + hideNumbers(m.group(2))));
        return GROUP.matcher(s).replaceAll(m -> Matcher.quoteReplacement(hideNumbers(m.group())));
    }

    private static boolean chosen(Mode mode, boolean baritone) {
        return switch (mode) {
            case OFF -> false;
            case BARITONE -> baritone;
            case ALL -> true;
            case ALL_BUT_BARITONE -> !baritone;
        };
    }

    private static String hideNumbers(String group) {
        return ANY_NUMBER.matcher(group).replaceAll(HIDDEN);
    }
}
