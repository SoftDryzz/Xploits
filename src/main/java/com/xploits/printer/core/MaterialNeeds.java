package com.xploits.printer.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * Need per material (printer spec §4): {@code total − placed − carried + placedNotSynced − syncedNotPlaced}, exact
 * whichever of the block update and the inventory sync of a placement arrives first (N-M14; the derivation is in the
 * phase-1 plan, Task 7). Unknown parts are not in {@code placed}, so they count as not built: fetch more, never less.
 */
public final class MaterialNeeds {
    private MaterialNeeds() {
    }

    /** Phase-1 targets of the whole build, by the item that places them. */
    public static Map<String, Long> totals(Map<BlockFacts, Long> wholeBuild) {
        Map<String, Long> m = new TreeMap<>();
        wholeBuild.forEach((facts, n) -> {
            if (PhaseRules.phaseOne(facts)) m.merge(facts.item(), n, Long::sum);
        });
        return m;
    }

    /** Blocks of the whole build left for a later phase (not air, not phase 1). */
    public static long later(Map<BlockFacts, Long> wholeBuild) {
        long n = 0;
        for (Map.Entry<BlockFacts, Long> e : wholeBuild.entrySet()) {
            if (!e.getKey().air() && !PhaseRules.phaseOne(e.getKey())) n += e.getValue();
        }
        return n;
    }

    public static Map<String, Long> need(Map<String, Long> total, Map<String, Integer> placed,
                                         Map<String, Integer> carried, Map<String, Integer> placedNotSynced,
                                         Map<String, Integer> syncedNotPlaced) {
        Map<String, Long> m = new TreeMap<>();
        total.forEach((item, t) -> {
            long n = t - placed.getOrDefault(item, 0) - carried.getOrDefault(item, 0)
                + placedNotSynced.getOrDefault(item, 0) - syncedNotPlaced.getOrDefault(item, 0);
            if (n > 0) m.put(item, n);
        });
        return m;
    }

    /** What the known remaining targets need beyond what is carried (phase 1's "material missing" list). */
    public static Map<String, Integer> shortOf(Map<String, Integer> remaining, Map<String, Integer> carried) {
        Map<String, Integer> m = new TreeMap<>();
        remaining.forEach((item, n) -> {
            int s = n - carried.getOrDefault(item, 0);
            if (s > 0) m.put(item, s);
        });
        return m;
    }
}
