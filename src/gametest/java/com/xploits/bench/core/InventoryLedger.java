package com.xploits.bench.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * The bench's "nothing lost" rule (restock spec §6; the printer's M5). Every item is counted by id at T0 and at the end —
 * the player's inventory, armour, offhand, cursor and shulker contents, and every container of the scene — and every
 * block of a build that appeared on the server is counted by the item that places it. An item is lost when its count
 * fell by more than the blocks of it that appeared: it left without becoming part of the build (dropped, thrown, placed
 * somewhere else, eaten by a desync). A picked-up drop only raises a count, which is never a loss. Pure.
 */
public final class InventoryLedger {
    private InventoryLedger() {
    }

    /** For each id, how much {@code after} holds over {@code before}; ids that did not grow are absent. */
    public static Map<String, Long> increase(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> grown = new TreeMap<>();
        after.forEach((id, n) -> {
            long d = n - before.getOrDefault(id, 0L);
            if (d > 0) grown.put(id, d);
        });
        return grown;
    }

    /** For each id, {@code atT0 - atEnd - placed} where that is positive; empty when nothing was lost. */
    public static Map<String, Long> lost(Map<String, Long> atT0, Map<String, Long> atEnd, Map<String, Long> placed) {
        Map<String, Long> gone = new TreeMap<>();
        atT0.forEach((id, n) -> {
            long d = n - atEnd.getOrDefault(id, 0L) - placed.getOrDefault(id, 0L);
            if (d > 0) gone.put(id, d);
        });
        return gone;
    }

    /** "minecraft:dirt 1, minecraft:stone 3" in id order (a name before every count, never three numbers in a row). */
    public static String words(Map<String, Long> counts) {
        if (counts.isEmpty()) return "nothing";
        StringBuilder s = new StringBuilder();
        new TreeMap<>(counts).forEach((id, n) -> {
            if (!s.isEmpty()) s.append(", ");
            s.append(id).append(' ').append(n);
        });
        return s.toString();
    }
}
