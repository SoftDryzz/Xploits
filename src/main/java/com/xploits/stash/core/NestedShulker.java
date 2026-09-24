package com.xploits.stash.core;

import java.util.Map;

/**
 * A shulker inside a container (spec §4.1). It keeps the slot it was in because that is the only
 * thing that identifies an unnamed shulker; when it has a name, the name wins (spec §4.3).
 *
 * @param customName custom name, or null if it has none
 */
public record NestedShulker(int slot, String customName, String color, Map<String, Integer> items) {
    public NestedShulker {
        items = Map.copyOf(items);
    }

    /** Its name if it has one; otherwise its slot. It is the identity it is tracked by. */
    public String identity() {
        return customName != null && !customName.isBlank() ? customName : "slot:" + slot;
    }

    public int totalOf(String itemId) {
        return items.getOrDefault(itemId, 0);
    }
}
