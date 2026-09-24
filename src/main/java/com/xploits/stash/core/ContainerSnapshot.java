package com.xploits.stash.core;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of a container's contents (spec §4.1). Items are added up by type, not slot by slot:
 * nothing in the module needs to know which slot each stack was in.
 *
 * @param seenAt epoch millis of when it was taken
 */
public record ContainerSnapshot(ContainerKey key, ContainerType type, long seenAt,
                                Map<String, Integer> items, List<NestedShulker> nested) {
    public ContainerSnapshot {
        items = Map.copyOf(items);
        nested = List.copyOf(nested);
    }

    /** How many of that item there are, counting those inside shulkers too. */
    public int totalOf(String itemId) {
        int total = items.getOrDefault(itemId, 0);
        for (NestedShulker shulker : nested) total += shulker.totalOf(itemId);
        return total;
    }
}
