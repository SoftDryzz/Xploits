package com.xploits.printer.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Placements sent and not yet confirmed (printer spec §5.6), copied from surround++'s pending rule (M4) with the window
 * from the measured round trip. Placed = the world shows the target; failed = an acknowledgement covering our sequence
 * arrived and the world does not show it; expired = neither within the window. Failures and expiries count towards K.
 * Digs (rulings P4, P23) are tracked alongside: dug = the world no longer shows the block; expired = still there after the
 * window plus the grace given at {@code digSent}, which counts towards K. A dig is never FAILED: vanilla can break a block
 * after it acknowledged our STOP, so an acknowledgement settles nothing for a dig (it only feeds the round-trip sample).
 */
public final class PendingBlocks {
    /** {@code DUG} is only ever the outcome of a dig; a dig's EXPIRED is told from a placement's by {@link Settled#dig()}. */
    public enum Outcome { PLACED, FAILED, EXPIRED, DUG }

    /** The {@code material} of a settled dig. */
    public static final String DIG = "";

    public record Settled(Pos pos, String material, Outcome outcome) {
        public boolean dig() {
            return material.equals(DIG);
        }
    }

    private static final int SAMPLES = 8;

    private static final class Entry {
        final Pos pos;
        final String material;
        final int sequence;
        final long sent;
        boolean acked;
        boolean placed;
        boolean synced;
        boolean sampled;
        long placedAt = -1;

        Entry(Pos pos, String material, int sequence, long sent) {
            this.pos = pos;
            this.material = material;
            this.sequence = sequence;
            this.sent = sent;
        }
    }

    private static final class Dig {
        final Pos pos;
        final int sequence;
        final long sent;
        final int grace;
        boolean acked;

        Dig(Pos pos, int sequence, long sent, int grace) {
            this.grace = grace;
            this.pos = pos;
            this.sequence = sequence;
            this.sent = sent;
        }
    }

    private final PrinterLimits limits;
    private final List<Dig> digs = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Pos, Integer> failures = new HashMap<>();
    private final ArrayDeque<Integer> samples = new ArrayDeque<>();

    public PendingBlocks(PrinterLimits limits) {
        this.limits = limits;
    }

    public void sent(Pos pos, String material, int sequence, long tick) {
        Objects.requireNonNull(material, "material");
        if (material.isEmpty()) throw new IllegalArgumentException("a placement needs a material");
        entries.add(new Entry(pos, material, sequence, tick));
    }

    /** Our STOP packet (or an instant START) for {@code pos}: in flight until the world shows the block gone. */
    public void digSent(Pos pos, int sequence, long tick, int graceTicks) {
        digs.add(new Dig(pos, sequence, tick, Math.max(0, graceTicks)));
    }

    /** {@code PlayerActionResponseS2CPacket}: the server has handled every sequence up to this one. */
    public void acknowledged(int sequence, long tick) {
        for (Entry e : entries) {
            if (e.sequence > sequence || e.acked) continue;
            e.acked = true;
            sample(e, tick);
        }
        for (Dig d : digs) {
            if (d.sequence > sequence || d.acked) continue;
            d.acked = true;
            record(tick - d.sent);
        }
    }

    /** The client's count of {@code material} dropped by {@code count}: our placements, oldest placed first. */
    public void inventoryDrop(String material, int count) {
        for (int i = 0; i < count; i++) {
            Entry target = first(material, true);
            if (target == null) target = first(material, false);
            if (target == null) return;
            target.synced = true;
        }
    }

    private Entry first(String material, boolean placed) {
        for (Entry e : entries) {
            if (e.material.equals(material) && e.placed == placed && !e.synced) return e;
        }
        return null;
    }

    /**
     * Same as the 4-argument form with every dug block read as still there: digs can only EXPIRE through it (counting
     * towards K). The module must use the 4-argument form.
     */
    public List<Settled> settle(long tick, int pingTicks, Predicate<Pos> placedInWorld) {
        return settle(tick, pingTicks, placedInWorld, pos -> true);
    }

    /**
     * @param placedInWorld   the world shows the placement target at the position
     * @param dugBlockStillThere the world still shows the block we dug at the position
     */
    public List<Settled> settle(long tick, int pingTicks, Predicate<Pos> placedInWorld,
                                Predicate<Pos> dugBlockStillThere) {
        int window = windowTicks(pingTicks);
        List<Settled> out = new ArrayList<>();
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!e.placed) {
                if (placedInWorld.test(e.pos)) {
                    e.placed = true;
                    e.placedAt = tick;
                    sample(e, tick);
                    out.add(new Settled(e.pos, e.material, Outcome.PLACED));
                } else if (e.acked) {
                    failures.merge(e.pos, 1, Integer::sum);
                    out.add(new Settled(e.pos, e.material, Outcome.FAILED));
                    it.remove();
                    continue;
                } else if (tick - e.sent > window) {
                    failures.merge(e.pos, 1, Integer::sum);
                    out.add(new Settled(e.pos, e.material, Outcome.EXPIRED));
                    it.remove();
                    continue;
                }
            }
            if (e.placed && e.synced) it.remove();
            else if (e.placed && tick - e.placedAt > 4L * window) it.remove();
        }
        Iterator<Dig> dit = digs.iterator();
        while (dit.hasNext()) {
            Dig d = dit.next();
            Outcome outcome;
            if (!dugBlockStillThere.test(d.pos)) {
                if (!d.acked) record(tick - d.sent);
                outcome = Outcome.DUG;
            } else if (tick - d.sent > window + d.grace) outcome = Outcome.EXPIRED;
            else continue;
            if (outcome == Outcome.EXPIRED) failures.merge(d.pos, 1, Integer::sum);
            out.add(new Settled(d.pos, DIG, outcome));
            dit.remove();
        }
        return out;
    }

    /** {@code max(pendingMinTicks, 2 × round trip)}, the round trip being the larger of the ping and the slowest recent answer. */
    public int windowTicks(int pingTicks) {
        int slowest = 0;
        for (int s : samples) slowest = Math.max(slowest, s);
        return Math.max(limits.pendingMinTicks(), 2 * Math.max(pingTicks, slowest));
    }

    public boolean pending(Pos pos) {
        for (Entry e : entries) {
            if (!e.placed && e.pos.equals(pos)) return true;
        }
        return false;
    }

    public Set<Pos> inFlight() {
        Set<Pos> set = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (!e.placed) set.add(e.pos);
        }
        return Collections.unmodifiableSet(set);
    }

    /** Positions whose dig was sent and not yet settled. */
    public Set<Pos> digging() {
        Set<Pos> set = new LinkedHashSet<>();
        for (Dig d : digs) set.add(d.pos);
        return Collections.unmodifiableSet(set);
    }

    public int failures(Pos pos) {
        return failures.getOrDefault(pos, 0);
    }

    public boolean skipped(Pos pos) {
        return failures(pos) >= limits.failedPlacements();
    }

    public Set<Pos> skipped() {
        Set<Pos> set = new LinkedHashSet<>();
        failures.forEach((pos, n) -> {
            if (n >= limits.failedPlacements()) set.add(pos);
        });
        return Collections.unmodifiableSet(set);
    }

    public Map<String, Integer> placedNotSynced() {
        Map<String, Integer> m = new TreeMap<>();
        for (Entry e : entries) {
            if (e.placed && !e.synced) m.merge(e.material, 1, Integer::sum);
        }
        return m;
    }

    public Map<String, Integer> syncedNotPlaced() {
        Map<String, Integer> m = new TreeMap<>();
        for (Entry e : entries) {
            if (!e.placed && e.synced) m.merge(e.material, 1, Integer::sum);
        }
        return m;
    }

    /** Nothing in flight and nothing waiting for its inventory sync. */
    public boolean idle() {
        return entries.isEmpty() && digs.isEmpty();
    }

    private void sample(Entry e, long tick) {
        if (e.sampled) return;
        e.sampled = true;
        record(tick - e.sent);
    }

    private void record(long ticks) {
        samples.addLast((int) Math.min(Integer.MAX_VALUE, Math.max(0, ticks)));
        while (samples.size() > SAMPLES) samples.removeFirst();
    }
}
