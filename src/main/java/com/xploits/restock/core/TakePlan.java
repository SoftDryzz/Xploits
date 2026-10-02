package com.xploits.restock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What to take from an open container this tick (restock spec §3 "Take"): one QUICK_MOVE of a whole stack, which vanilla
 * moves into same-item stacks and then empty slots of the player's 36, as much as fits, never into the offhand or the
 * armour and never onto the cursor. First the trip's material, then every other material still needed, in the order
 * given; from each, the largest stack that fits at all (ties: the lower slot). Over-take is at most one stack.
 */
public final class TakePlan {
    /** One container slot as the adapter reads it; {@code room}: how many of that exact stack the player's slots take. */
    public record Slot(int slot, String item, int count, int room) {
    }

    public sealed interface Step permits Click, Done, NothingFits {
    }

    /** QUICK_MOVE this container slot. */
    public record Click(int slot) implements Step {
    }

    /** Nothing more to take; {@code materialThere}: the trip's material is in the container (false: it was stale). */
    public record Done(boolean materialThere) implements Step {
    }

    /** The trip's material is there and still needed, and none of it fits. */
    public record NothingFits() implements Step {
    }

    private TakePlan() {
    }

    public static Step next(List<Slot> container, String material, Map<String, Long> need) {
        List<String> order = new ArrayList<>();
        order.add(material);
        for (String m : need.keySet()) {
            if (!m.equals(material)) order.add(m);
        }
        for (String m : order) {
            if (need.getOrDefault(m, 0L) <= 0) continue;
            Slot best = null;
            for (Slot s : container) {
                if (!s.item().equals(m) || s.count() <= 0 || s.room() <= 0) continue;
                if (best == null || s.count() > best.count() || (s.count() == best.count() && s.slot() < best.slot())) {
                    best = s;
                }
            }
            if (best != null) return new Click(best.slot());
        }
        boolean there = false;
        for (Slot s : container) {
            if (s.item().equals(material) && s.count() > 0) there = true;
        }
        if (there && need.getOrDefault(material, 0L) > 0) return new NothingFits();
        return new Done(there);
    }
}
