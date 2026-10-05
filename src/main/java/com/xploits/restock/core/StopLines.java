package com.xploits.restock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The order in which a stop speaks (owner ruling R42, M7): the stop itself first, then the stop of an unpack it was
 * finishing for ({@link RestockText#UNPACK_DRAINED}), then the guards' stop that came with it
 * ({@link RestockText#STOPPED_ALSO}), then what ending the session left to say. A line that does not apply is
 * {@code null}; generic so the order is pinned on keys.
 */
public final class StopLines {
    private StopLines() {
    }

    public static <T> List<T> order(T stopped, T drained, T also, List<T> after) {
        List<T> lines = new ArrayList<>(3 + after.size());
        lines.add(stopped);
        if (drained != null) lines.add(drained);
        if (also != null) lines.add(also);
        lines.addAll(after);
        return lines;
    }
}
