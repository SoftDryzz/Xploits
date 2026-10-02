package com.xploits.restock.core;

import com.xploits.printer.core.Pos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The positions that hold the build's block with fewer items than the build wants there: one slab where a double slab
 * goes, two of four candles, three snow layers of eight. {@code BuildIndex} counts them placed (the same block id), so
 * its missing positions never list their material, yet the rest of the need sits there; this tally names those
 * materials so they can still be due (deferred m1, after ruling R31). Kept beside the index, cleared with it.
 */
public final class PartlyPlaced {
    private final Map<Pos, String> shortAt = new HashMap<>();

    /** Replaces the position's previous value; a null material means it is not short. */
    public void set(Pos pos, String material) {
        if (material == null) shortAt.remove(pos);
        else shortAt.put(pos, material);
    }

    /** The materials short somewhere, sorted. */
    public Set<String> materials() {
        return Collections.unmodifiableSet(new TreeSet<>(shortAt.values()));
    }

    public void clear() {
        shortAt.clear();
    }

    public int size() {
        return shortAt.size();
    }
}
