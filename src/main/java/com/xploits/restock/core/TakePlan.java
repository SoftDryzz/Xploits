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
 * <p>
 * Phase B (spec §2; owner rulings R40, R44): a shulker box restock may carry — the adapter gives its contents — is
 * carried whole when no loose stack of the material is there, the one holding the most; an empty box restock borrowed
 * from this container goes back into it first.
 */
public final class TakePlan {
    /**
     * One slot as the adapter reads it: a container slot, or — in {@code returning} — a slot of the player's part of the
     * same screen. {@code room}: how many of that exact stack the other side takes (for a box restock would carry,
     * {@link #carryRoom}); {@code holdsItems}: the stack carries items of its own (ruling R34); {@code contents}: what a
     * shulker box restock may carry holds, by item id — empty for anything else, and for a filled box whose own item the
     * build places (it stays filled only, never carried).
     */
    public record Slot(int slot, String item, int count, int room, boolean holdsItems, Map<String, Integer> contents) {
        public Slot {
            contents = Map.copyOf(contents);
            if (!contents.isEmpty() && !holdsItems) throw new IllegalArgumentException("contents are items held");
        }

        /** A stack that holds nothing. */
        public Slot(int slot, String item, int count, int room) {
            this(slot, item, count, room, false, Map.of());
        }

        /** A stack that may hold items restock never carries (ruling R34). */
        public Slot(int slot, String item, int count, int room, boolean holdsItems) {
            this(slot, item, count, room, holdsItems, Map.of());
        }

        /** A shulker box restock may carry, holding {@code contents}. */
        public Slot(int slot, String item, int count, int room, Map<String, Integer> contents) {
            this(slot, item, count, room, !contents.isEmpty(), contents);
        }
    }

    public sealed interface Step permits Click, Done, NothingFits {
    }

    /**
     * QUICK_MOVE this slot. {@code box}: the click moves a whole shulker box — carried out, or given back — whose
     * server answer the trip waits for before anything else happens in the screen (ruling R70).
     */
    public record Click(int slot, boolean box) implements Step {
        /** A loose stack's click. */
        public Click(int slot) {
            this(slot, false);
        }
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

    /** Phase A's take: no box carried, nothing given back. */
    public static Step next(List<Slot> all, String material, Map<String, Long> need) {
        return next(all, List.of(), material, need, false);
    }

    /**
     * Phase B's take: first an empty borrowed box goes back ({@code returning}, the player-side slots the adapter chose,
     * with the container's free slots as their room); then, for each material in order, its largest loose stack that
     * fits, else — with {@code carry} — the box holding the most of it. With nothing returning and {@code carry} false
     * it is phase A's take, step for step.
     */
    public static Step next(List<Slot> all, List<Slot> returning, String material, Map<String, Long> need,
                            boolean carry) {
        for (Slot r : returning) {
            if (r.count() > 0 && r.room() > 0) return new Click(r.slot(), true);
        }
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
            if (!carry) continue;
            Slot box = null;
            int boxHeld = 0;
            for (Slot s : all) {
                int held = s.contents().getOrDefault(m, 0);
                if (held <= 0 || s.room() <= 0) continue;
                if (box == null || held > boxHeld || (held == boxHeld && s.slot() < box.slot())) {
                    box = s;
                    boxHeld = held;
                }
            }
            if (box != null) return new Click(box.slot(), true);
        }
        boolean there = false;
        for (Slot s : container) {
            if (s.item().equals(material) && s.count() > 0) there = true;
        }
        if (carry) {
            for (Slot s : all) {
                if (s.contents().getOrDefault(material, 0) > 0) there = true;
            }
        }
        if (there && need.getOrDefault(material, 0L) > 0) return new NothingFits();
        boolean filled = false;
        for (Slot s : all) {
            if (s.holdsItems() && s.item().equals(material) && s.count() > 0) filled = true;
        }
        return new Done(there, !there && filled);
    }

    /**
     * What a QUICK_MOVE of a stack of {@code count} may use, keeping {@code reserve} empty slots free (the box restock
     * sets down comes back to one): the room in the same-item stacks plus the empty slots beyond the reserve. With a
     * reserve, a stack that does not fit whole gets no room at all — vanilla would put the rest into the slot kept free
     * (pre-flight 19-6). Without one it is phase A's room: vanilla moves what fits.
     */
    public static int room(int sameItemRoom, int emptySlots, int maxCount, int count, int reserve) {
        int room = sameItemRoom + Math.max(0, emptySlots - reserve) * maxCount;
        if (reserve > 0 && count > room) return 0;
        return room;
    }

    /**
     * A box restock would carry fits while a hotbar slot is empty — vanilla's QUICK_MOVE out of a container puts a box
     * into the last empty hotbar slot first, and into the main inventory only with the hotbar full — and one more slot
     * is, for the take at the build (pre-flight 17-4).
     */
    public static int carryRoom(int emptyHotbar, int emptySlots) {
        return emptyHotbar >= 1 && emptySlots >= 2 ? 1 : 0;
    }
}
