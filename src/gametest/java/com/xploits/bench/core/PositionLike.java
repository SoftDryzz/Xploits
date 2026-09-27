package com.xploits.bench.core;

import java.util.regex.Pattern;

/**
 * What the report hygiene scan takes for a position (spec {@code 2026-09-25-ingame-bench}, §Proving the
 * bench works): three numbers in a row separated by spaces or commas. Pure, so the unit tests can hold
 * the report's texts against the very pattern the scan uses.
 */
public final class PositionLike {
    public static final Pattern PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?[ ,]+-?\\d+(\\.\\d+)?[ ,]+-?\\d+(\\.\\d+)?");

    private PositionLike() {
    }

    /** Whether the line holds something that looks like a position. */
    public static boolean in(String line) {
        return PATTERN.matcher(line).find();
    }
}
