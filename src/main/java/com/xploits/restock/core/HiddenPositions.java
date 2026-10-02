package com.xploits.restock.core;

import java.util.Optional;

/**
 * How restock's records print (spec §3, "no coordinates anywhere"; deferred L6): a record that holds a position overrides
 * {@code toString} and shows {@link #HIDDEN} in its place, so a position can never reach a log line or an exception's
 * message through one. Every other field prints as usual.
 */
public final class HiddenPositions {
    /** What a record prints instead of a position. */
    public static final String HIDDEN = "(position)";

    private HiddenPositions() {
    }

    /** {@link #HIDDEN} for a position that is there, {@code none} for an empty one. */
    public static String of(Optional<?> position) {
        return position.isPresent() ? HIDDEN : "none";
    }
}
