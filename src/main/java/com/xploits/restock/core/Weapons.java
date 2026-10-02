package com.xploits.restock.core;

import java.util.Set;

/**
 * P17, corrected by pre-flight V11: a weapon is never a digging tool, so a fight's weapon is never worn or swapped
 * away. Swords, axes and spears by their item tags; the mace and the trident by item (every 1.21.11 tool carries a
 * weapon component, so the component cannot tell them apart). Pure: the adapter passes the item id and its tag ids.
 */
public final class Weapons {
    private static final Set<String> TAGS = Set.of("minecraft:swords", "minecraft:axes", "minecraft:spears");
    private static final Set<String> ITEMS = Set.of("minecraft:mace", "minecraft:trident");

    private Weapons() {
    }

    /** {@code item}: its id; {@code tags}: the ids of the item tags it is in. */
    public static boolean of(String item, Set<String> tags) {
        if (ITEMS.contains(item)) return true;
        for (String t : tags) {
            if (TAGS.contains(t)) return true;
        }
        return false;
    }
}
