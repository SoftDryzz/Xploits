package com.xploits.restock.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which materials ran out while the build still needs them, in the order they ran out (restock spec §3 "Trigger"). A
 * material is out when it is needed ({@link RestockNeeds#need} lists it), the player carries none, and the index knows
 * at least one position where it is missing (ruling R31): a position the index cannot see — Litematica's rendering off,
 * a part of the build in chunks not loaded — counts as not built for how much to take, but never sends a trip by
 * itself, or every finished material would be fetched again. It is due for a trip only once the index has completed
 * {@code duePasses} full scan passes since it ran out: the pass in progress may have read a block before
 * {@code litematica-printer} placed it with the last item, and a trip for a material the build no longer needs is a
 * walk away from the build for nothing (Review Focus 1). A material no source has is noted once and left out until it
 * is carried again, no longer needed, or the sources change.
 */
public final class RunOut {
    private record Out(long tick, int passes) {
    }

    private final int duePasses;
    private final Map<String, Out> out = new LinkedHashMap<>();
    private final Set<String> nowhere = new HashSet<>();

    public RunOut(int duePasses) {
        if (duePasses < 1) throw new IllegalArgumentException("duePasses " + duePasses);
        this.duePasses = duePasses;
    }

    /**
     * @param need         what the build still needs beyond what is carried (positive entries)
     * @param carried      what the player carries, main inventory and hotbar
     * @param passes       full passes of the index's scan so far
     * @param knownMissing the materials the index knows at least one missing position of
     * @return the due materials, the first to run out first, ties by item id
     */
    public List<String> due(long tick, Map<String, Long> need, Map<String, Integer> carried, int passes,
                            Set<String> knownMissing) {
        out.keySet().removeIf(item -> !isOut(item, need, carried, knownMissing));
        nowhere.removeIf(item -> !isOut(item, need, carried, knownMissing));
        for (String item : need.keySet()) {
            if (isOut(item, need, carried, knownMissing)) out.putIfAbsent(item, new Out(tick, passes));
        }
        List<Map.Entry<String, Out>> ready = new ArrayList<>();
        for (Map.Entry<String, Out> e : out.entrySet()) {
            if (passes >= e.getValue().passes() + duePasses && !nowhere.contains(e.getKey())) ready.add(e);
        }
        ready.sort(Comparator.<Map.Entry<String, Out>>comparingLong(e -> e.getValue().tick())
            .thenComparing(Map.Entry::getKey));
        return ready.stream().map(Map.Entry::getKey).toList();
    }

    /** No source within reach has {@code item}: it is said once and left out of {@link #due}. */
    public void nowhere(String item) {
        nowhere.add(item);
    }

    public boolean isNowhere(String item) {
        return nowhere.contains(item);
    }

    /** A container was marked or unmarked, or stash-keeper's index changed: every material is tried again. */
    public void sourcesChanged() {
        nowhere.clear();
    }

    private static boolean isOut(String item, Map<String, Long> need, Map<String, Integer> carried,
                                 Set<String> knownMissing) {
        return need.getOrDefault(item, 0L) > 0 && carried.getOrDefault(item, 0) <= 0 && knownMissing.contains(item);
    }
}
