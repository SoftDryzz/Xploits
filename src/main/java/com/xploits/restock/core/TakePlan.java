package com.xploits.restock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What to take from an open container this tick (restock spec §3 "Take"): one QUICK_MOVE of a whole stack, which vanilla
 * moves into same-item stacks and then empty slots of the player's 36, as much as fits, never into the offhand or the
 * armour and never onto the cursor. First the trip's material, then every other material still needed, in the order
 * given; from each, the largest stack that fits at all (ties: the lower slot). Over-take is at most one stack. A stack
 * that holds items of its own — a shulker box with a stash in it — is never taken as a block (ruling R34): the printer
 * would place it, contents and all, into the build; for the trip it is as if it were not there.
 */
public final class TakePlan {
    /**
     * One container slot as the adapter reads it; {@code room}: how many of that exact stack the player's slots take;
     * {@code holdsItems}: the stack carries items of its own (a shulker box, or any block item, with contents).
     */
    public record Slot(int slot, String item, int count, int room, boolean holdsItems) {
        /** A stack that holds nothing. */
        public Slot(int slot, String item, int count, int room) {
            this(slot, item, count, room, false);
        }
    }

    public sealed interface Step permits Click, Done, NothingFits {
    }

    /** QUICK_MOVE this container slot. */
    public record Click(int slot) implements Step {
    }

    /**
     * Nothing more to take; {@code materialThere}: the trip's material is in the container (false: it was stale);
     * {@code onlyFilled}: it is not, except in stacks that hold items of their own, which are never taken (the player is
     * told that, not that the container lost it).
     */
    public record Done(boolean materialThere, boolean onlyFilled) implements Step {
        public Done {
            if (materialThere && onlyFilled) throw new IllegalArgumentException("a material there is not only filled");
        }

        /** Nothing filled in the way. */
        public Done(boolean materialThere) {
            this(materialThere, false);
        }
    }

    /** The trip's material is there and still needed, and none of it fits. */
    public record NothingFits() implements Step {
    }

    private TakePlan() {
    }

    public static Step next(List<Slot> all, String material, Map<String, Long> need) {
        List<Slot> container = all.stream().filter(s -> !s.holdsItems()).toList();
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
        boolean filled = false;
        for (Slot s : all) {
            if (s.holdsItems() && s.item().equals(material) && s.count() > 0) filled = true;
        }
        return new Done(there, !there && filled);
    }
}
