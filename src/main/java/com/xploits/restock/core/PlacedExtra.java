package com.xploits.restock.core;

import com.xploits.printer.core.Pos;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Items beyond one that placed positions already hold. {@code BuildIndex} counts positions while the build's totals count
 * items, so a finished double slab or a four-candle state would leave a need that never clears; this tally holds the
 * difference. Sparse: only positions worth more than one item are kept.
 */
public final class PlacedExtra {
    private record Entry(String material, int extra) {
    }

    private final Map<Pos, Entry> entries = new HashMap<>();

    /** Replaces the position's previous value; {@code extra <= 0} or no material removes it. */
    public void set(Pos pos, String material, int extra) {
        if (material == null || extra <= 0) entries.remove(pos);
        else entries.put(pos, new Entry(material, extra));
    }

    /** The sum per material, sorted. */
    public Map<String, Integer> byMaterial() {
        Map<String, Integer> m = new TreeMap<>();
        for (Entry e : entries.values()) m.merge(e.material(), e.extra(), Integer::sum);
        return m;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
