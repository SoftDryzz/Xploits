package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The shulker boxes restock carried away from a container in this session (restock spec §2 phase B; owner ruling R44):
 * which kind — item and custom name, "" for none, the only thing that tells one from the player's own — from which
 * container in which dimension, one entry per box. A borrowed box goes back only once it is empty, and never more of a
 * kind than were borrowed from that container. While nothing runs the ledger is trimmed to the boxes the player carries:
 * it may forget a box, never invent one. Positions stay in memory and are never printed.
 */
public final class BorrowedShulkers {
    /** What tells one shulker box from another for restock: its item and its custom name. */
    public record Kind(String item, String name) {
        public Kind {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(name, "name");
        }
    }

    public record Borrowed(Kind kind, String dimension, Pos origin) {
        public Borrowed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(origin, "origin");
        }

        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "Borrowed[kind=" + kind + ", dimension=" + dimension + ", origin=" + HiddenPositions.HIDDEN + "]";
        }
    }

    /**
     * One box the player carries: the slot the caller would click (a screen slot, or an inventory index), its kind, and
     * whether it holds nothing.
     */
    public record Held(int slot, Kind kind, boolean empty) {
        public Held {
            Objects.requireNonNull(kind, "kind");
        }
    }

    private final List<Borrowed> list = new ArrayList<>();

    public void borrow(Borrowed b) {
        list.add(Objects.requireNonNull(b, "borrowed"));
    }

    /** One box of {@code kind} went back into {@code origin}: its oldest entry there leaves; false when there was none. */
    public boolean giveBack(String dimension, Pos origin, Kind kind) {
        for (int i = 0; i < list.size(); i++) {
            Borrowed b = list.get(i);
            if (b.kind().equals(kind) && b.dimension().equals(dimension) && b.origin().equals(origin)) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    public int count() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    /** Some box of {@code kind} is borrowed, from any container. */
    public boolean isBorrowed(Kind kind) {
        for (Borrowed b : list) {
            if (b.kind().equals(kind)) return true;
        }
        return false;
    }

    /**
     * The carried boxes that go back into {@code origin}: empty ones of a kind borrowed from it, at most as many of each
     * kind as were, in {@code held}'s order, each with the container's free slots as its room.
     */
    public List<TakePlan.Slot> toReturn(String dimension, Pos origin, List<Held> held, int freeSlots) {
        Map<Kind, Integer> left = new HashMap<>();
        for (Borrowed b : list) {
            if (b.dimension().equals(dimension) && b.origin().equals(origin)) left.merge(b.kind(), 1, Integer::sum);
        }
        List<TakePlan.Slot> out = new ArrayList<>();
        for (Held h : held) {
            if (!h.empty()) continue;
            int n = left.getOrDefault(h.kind(), 0);
            if (n <= 0) continue;
            left.put(h.kind(), n - 1);
            out.add(new TakePlan.Slot(h.slot(), h.kind().item(), 1, freeSlots));
        }
        return out;
    }

    /**
     * Where one last trip goes once the build is done: the nearest origin in {@code dimension}, not in {@code skip}, that
     * a carried empty box goes back to (to the block's centre; ties by x, y, z).
     */
    public Optional<Pos> lastTripOrigin(String dimension, Point from, List<Held> held, Set<Pos> skip) {
        Pos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Borrowed b : list) {
            Pos o = b.origin();
            if (!b.dimension().equals(dimension) || skip.contains(o)) continue;
            if (toReturn(dimension, o, held, 1).isEmpty()) continue;
            double d = o.distanceSq(from);
            if (d < bestD || (d == bestD && before(o, best))) {
                best = o;
                bestD = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Forgets the entries of a kind the player no longer carries as many of (put away by hand, a refused carry): the newest first. */
    public void trim(Map<Kind, Integer> carried) {
        Map<Kind, Integer> entries = new HashMap<>();
        for (Borrowed b : list) entries.merge(b.kind(), 1, Integer::sum);
        for (Map.Entry<Kind, Integer> e : entries.entrySet()) {
            int extra = e.getValue() - carried.getOrDefault(e.getKey(), 0);
            for (int i = list.size() - 1; i >= 0 && extra > 0; i--) {
                if (list.get(i).kind().equals(e.getKey())) {
                    list.remove(i);
                    extra--;
                }
            }
        }
    }

    /** How many borrowed boxes the player carries now: per kind, the fewer of the entries and the boxes carried. */
    public int carried(Map<Kind, Integer> carried) {
        Map<Kind, Integer> entries = new HashMap<>();
        for (Borrowed b : list) entries.merge(b.kind(), 1, Integer::sum);
        int n = 0;
        for (Map.Entry<Kind, Integer> e : entries.entrySet()) {
            n += Math.min(e.getValue(), carried.getOrDefault(e.getKey(), 0));
        }
        return n;
    }

    private static boolean before(Pos a, Pos b) {
        if (b == null) return true;
        if (a.x() != b.x()) return a.x() < b.x();
        if (a.y() != b.y()) return a.y() < b.y();
        return a.z() < b.z();
    }
}
