package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * One container visit's shulker box moves, for {@link #settle} (ruling R70): the boxes of each kind the player
     * carried in slots 0–35 right before the visit's first box click, holding items or empty, and the carries and
     * give-backs whose click packets left. Positions stay in memory and are never printed.
     */
    public static final class Visit {
        private final Map<Kind, Integer> filledBefore = new HashMap<>();
        private final Map<Kind, Integer> emptyBefore = new HashMap<>();
        private final List<Borrowed> carried = new ArrayList<>();
        private final List<Borrowed> givenBack = new ArrayList<>();

        /** {@code before}: the boxes in slots 0–35 right before the visit's first box click. */
        public Visit(List<Held> before) {
            tally(before, filledBefore, emptyBefore);
        }

        /** A carry whose click packet left, already noted in the ledger ({@link #borrow}). */
        public void borrowed(Borrowed b) {
            carried.add(Objects.requireNonNull(b, "borrowed"));
        }

        /** A give-back whose click packet left, its entry already gone from the ledger ({@link #giveBack}). */
        public void gaveBack(Borrowed b) {
            givenBack.add(Objects.requireNonNull(b, "given back"));
        }

        /**
         * A box this visit carried is no longer carried — the server refused its click and its correction arrived, or
         * the player put it back: the visit carries nothing more (each further carry would only be refused too).
         */
        public boolean cameBack(List<Held> now) {
            return !cameBackKinds(now).isEmpty();
        }

        /**
         * The kinds of which fewer boxes holding items arrived than this visit carried: a carry of that kind came back
         * (ruling R71: nothing of that kind goes back in this visit, {@link #toReturn(String, Pos, List, int, Visit)}).
         */
        public Set<Kind> cameBackKinds(List<Held> now) {
            Map<Kind, Integer> filledNow = new HashMap<>();
            tally(now, filledNow, new HashMap<>());
            Map<Kind, Integer> noted = new HashMap<>();
            for (Borrowed b : carried) noted.merge(b.kind(), 1, Integer::sum);
            Set<Kind> back = new HashSet<>();
            noted.forEach((kind, n) -> {
                if (n > arrived(kind, filledNow)) back.add(kind);
            });
            return back;
        }

        /** How many boxes of {@code kind} holding items came into slots 0–35 since the visit's first box click. */
        private int arrived(Kind kind, Map<Kind, Integer> filledNow) {
            return Math.max(0, filledNow.getOrDefault(kind, 0) - filledBefore.getOrDefault(kind, 0));
        }
    }

    /**
     * What a visit's settle kept of its carries, for {@link #recheck} (ruling R71): per kind, how many, one entry that
     * stands for them (a visit is at one container, so its carries of a kind are equal entries), and how many boxes of
     * that kind the player carried at the settle, holding items or not; and how many {@link #recheck} forgot since.
     */
    public static final class Kept {
        private final Map<Kind, Borrowed> entry = new HashMap<>();
        private final Map<Kind, Integer> count = new HashMap<>();
        private final Map<Kind, Integer> boxesAtSettle;
        private final Map<Kind, Integer> forgotten = new HashMap<>();

        private Kept(Map<Kind, Integer> boxesAtSettle) {
            this.boxesAtSettle = boxesAtSettle;
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

    /**
     * Ruling R70 at a container visit's close (and at a stop during one): the ledger keeps only what the server let go.
     * Per kind, of the carries noted in the visit at most as many stay as boxes of that kind holding items came into
     * slots 0–35 ({@code now} against the visit's start); the others are forgotten. A carry the server refused so adds
     * nothing — the player's own box of that kind never counts as borrowed — and a box carried again after its
     * refusal counts once. Per kind, a give-back stays done only as many times as empty boxes of that kind left the
     * player; the others get their entries back. Boxes holding items and empty ones are counted apart, so a give-back
     * and a carry of one kind in one visit never hide each other. Entries of one kind from one container are
     * interchangeable. Entries are given back before carries are forgotten (ruling R71), so an entry a refused
     * give-back used up never outlives a refused carry. What it kept goes to {@link #recheck}.
     */
    public Kept settle(Visit v, List<Held> now) {
        Map<Kind, Integer> filledNow = new HashMap<>();
        Map<Kind, Integer> emptyNow = new HashMap<>();
        tally(now, filledNow, emptyNow);
        Map<Kind, Integer> left = new HashMap<>();
        for (Borrowed b : v.givenBack) {
            left.computeIfAbsent(b.kind(), kind -> Math.max(0, v.emptyBefore.getOrDefault(kind, 0)
                - emptyNow.getOrDefault(kind, 0)));
            int n = left.get(b.kind());
            if (n > 0) {
                left.put(b.kind(), n - 1);
            } else {
                list.add(b);
            }
        }
        Map<Kind, Integer> extra = new HashMap<>();
        for (Borrowed b : v.carried) extra.merge(b.kind(), 1, Integer::sum);
        extra.replaceAll((kind, noted) -> noted - v.arrived(kind, filledNow));
        for (int i = v.carried.size() - 1; i >= 0; i--) {
            Borrowed b = v.carried.get(i);
            if (extra.get(b.kind()) <= 0) continue;
            if (list.remove(b)) extra.merge(b.kind(), -1, Integer::sum);
        }
        Map<Kind, Integer> boxes = new HashMap<>(filledNow);
        emptyNow.forEach((kind, n) -> boxes.merge(kind, n, Integer::sum));
        Kept kept = new Kept(boxes);
        Map<Kind, Integer> noted = new HashMap<>();
        for (Borrowed b : v.carried) {
            noted.merge(b.kind(), 1, Integer::sum);
            kept.entry.put(b.kind(), b);
        }
        noted.forEach((kind, n) -> kept.count.put(kind, Math.min(n, v.arrived(kind, filledNow))));
        return kept;
    }

    /**
     * Ruling R71 (R70's residual): a server that answered a carry later than the visit's answer wait leaves a ghost
     * box, which the settle took for an arrived one. When the next container screen brings the player's inventory from
     * the server ({@code now}), before that visit moves a box, the boxes of each kind a kept carry is of are counted
     * again — holding items or not, so an unpack in between changes nothing — and for each that vanished since the
     * settle one of those carries is forgotten, never more than were kept, each only once (it may run every tick). It
     * only ever forgets.
     */
    public void recheck(Kept kept, List<Held> now) {
        Map<Kind, Integer> boxesNow = new HashMap<>();
        tally(now, boxesNow, boxesNow);
        kept.count.forEach((kind, n) -> {
            int vanished = Math.max(0, kept.boxesAtSettle.getOrDefault(kind, 0) - boxesNow.getOrDefault(kind, 0));
            int target = Math.min(n, vanished);
            int done = kept.forgotten.getOrDefault(kind, 0);
            for (int i = done; i < target; i++) list.remove(kept.entry.get(kind));
            if (target > done) kept.forgotten.put(kind, target);
        });
    }

    /** The boxes in {@code held} by kind, holding items or empty. */
    private static void tally(List<Held> held, Map<Kind, Integer> filled, Map<Kind, Integer> empty) {
        for (Held h : held) (h.empty() ? empty : filled).merge(h.kind(), 1, Integer::sum);
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
     * kind as were, in {@code held}'s order, each with the container's free slots as its room. Two boxes of one kind
     * cannot be told apart, so the player's own are presumed empty first (owner ruling R44: they stay with the player):
     * per kind at most {@code emptyK - ownK} go back, where {@code ownK} is the boxes carried less the entries of that kind.
     */
    public List<TakePlan.Slot> toReturn(String dimension, Pos origin, List<Held> held, int freeSlots) {
        Map<Kind, Integer> entries = new HashMap<>();
        Map<Kind, Integer> left = new HashMap<>();
        for (Borrowed b : list) {
            entries.merge(b.kind(), 1, Integer::sum);
            if (b.dimension().equals(dimension) && b.origin().equals(origin)) left.merge(b.kind(), 1, Integer::sum);
        }
        Map<Kind, Integer> carried = new HashMap<>();
        Map<Kind, Integer> empty = new HashMap<>();
        for (Held h : held) {
            carried.merge(h.kind(), 1, Integer::sum);
            if (h.empty()) empty.merge(h.kind(), 1, Integer::sum);
        }
        for (Map.Entry<Kind, Integer> e : left.entrySet()) {
            int own = Math.max(0, carried.getOrDefault(e.getKey(), 0) - entries.getOrDefault(e.getKey(), 0));
            int budget = Math.max(0, empty.getOrDefault(e.getKey(), 0) - own);
            e.setValue(Math.min(e.getValue(), budget));
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
     * Ruling R71: {@link #toReturn(String, Pos, List, int)} during a container visit ({@code visit}, null when no box
     * moved yet). Nothing of a kind whose box this visit carried came back goes back: until the visit's close, that
     * carry's entry stays in the ledger, and R50's count would take the player's own empty box of that kind for the
     * borrowed one. Every other kind as before.
     */
    public List<TakePlan.Slot> toReturn(String dimension, Pos origin, List<Held> held, int freeSlots, Visit visit) {
        if (visit == null) return toReturn(dimension, origin, held, freeSlots);
        Set<Kind> withheld = visit.cameBackKinds(held);
        return toReturn(dimension, origin, held.stream().filter(h -> !withheld.contains(h.kind())).toList(), freeSlots);
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

    /**
     * Forgets the entries of a kind the player no longer carries as many of (a box put away by hand): the newest first.
     * It cannot tell a borrowed box from the player's own of that kind, so a carry the server refused is settled at the
     * visit's close instead ({@link #settle}, ruling R70).
     */
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
