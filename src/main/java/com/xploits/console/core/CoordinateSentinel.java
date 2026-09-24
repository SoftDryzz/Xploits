package com.xploits.console.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The last net against coordinates (console spec §7). The places that carry them are marked at the
 * source; this exists in case one slips through unmarked.
 *
 * <p>It fails closed and with precise patterns: a false positive costs a hidden line, which also says
 * it is hidden, and a false negative costs the player's base.
 */
public final class CoordinateSentinel {
    private static final List<Pattern> PATTERNS = List.of(
        // The ContainerKey.id() format: dimension@x,y,z.
        Pattern.compile("@-?\\d+,-?\\d+,-?\\d+"),
        // A Baritone goal or goto with two or three integers, with any prefix or none.
        Pattern.compile("(?<![\\p{L}\\p{N}])[^\\s\\p{L}\\p{N}]?(?:goal|goto)\\s+-?\\d+(?:\\s+-?\\d+){1,2}(?!\\d)",
            Pattern.CASE_INSENSITIVE),
        // The old AutoTravel.status() fragment, in Spanish: "en 1200, -800".
        Pattern.compile("(?<![\\p{L}\\p{N}])en -?\\d{3,}, -?\\d{3,}(?!\\d)"),
        // Its English equivalent: "at 1200, -800".
        Pattern.compile("(?<![\\p{L}\\p{N}])at -?\\d{3,}, -?\\d{3,}(?!\\d)"));

    private CoordinateSentinel() {
    }

    /** Whether the text looks like it carries coordinates. */
    public static boolean isSuspect(String text) {
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    /** What is left of a held text: that it was held and whom it came from, nothing else. */
    public static Msg held(String source) {
        return Msg.of(ConsoleText.HELD, "source", source);
    }
}
