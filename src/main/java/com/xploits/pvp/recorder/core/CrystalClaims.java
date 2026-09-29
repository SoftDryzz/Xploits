package com.xploits.pvp.recorder.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which end crystals are ours, for the bench only: a crystal is claimed as ours when it is added to the world at a
 * cell where we sent a placement not long before, and one placement claims at most one crystal. An opponent's
 * crystal that lands on a cell we placed on earlier (its placement was claimed already, or too old) is not ours.
 * Works for whichever module sent the placements, since it reads the packets.
 *
 * <p>The clock is the recorder's tick. A placement waits {@value #WINDOW_TICKS} ticks (one second) for its
 * crystal: the round trip of the placement packet plus the server tick plus the spawn packet, with room for a
 * ping of several hundred milliseconds and for the brain's pending lifetime, yet short enough that a
 * placement that never landed cannot claim a crystal of the opponent's a moment later.
 *
 * <p>Cells are packed block positions, in memory only; nothing here is ever logged or written.
 */
public final class CrystalClaims {
    public static final int WINDOW_TICKS = 20;
    /** How many claimed ids are kept (the latest). */
    public static final int CLAIMED_KEPT = 512;

    private record Placement(long cell, long tick) {
    }

    private final List<Placement> unclaimed = new ArrayList<>();
    private final Set<Integer> claimed = new LinkedHashSet<>();
    private final Set<Integer> seen = new LinkedHashSet<>();
    private long total;

    /** A crystal placement of ours was sent at {@code cell} on {@code tick}. */
    public void placed(long cell, long tick) {
        prune(tick);
        unclaimed.add(new Placement(cell, tick));
    }

    /**
     * A crystal was added to the world at {@code cell} on {@code tick}. Returns whether it is ours: an unclaimed
     * placement at that cell, sent in [tick - window, tick], is consumed (the oldest one).
     */
    public boolean added(int id, long cell, long tick) {
        if (claimed.contains(id)) return true;
        if (!seen.add(id)) return false;
        if (seen.size() > CLAIMED_KEPT) seen.remove(seen.iterator().next());
        prune(tick);
        for (int i = 0; i < unclaimed.size(); i++) {
            Placement p = unclaimed.get(i);
            if (p.cell() == cell && p.tick() <= tick) {
                unclaimed.remove(i);
                claimed.add(id);
                total++;
                if (claimed.size() > CLAIMED_KEPT) claimed.remove(claimed.iterator().next());
                return true;
            }
        }
        return false;
    }

    private void prune(long now) {
        unclaimed.removeIf(p -> now - p.tick() > WINDOW_TICKS);
    }

    public boolean isClaimed(int id) {
        return claimed.contains(id);
    }

    /** A copy of the claimed crystal ids (the latest {@value #CLAIMED_KEPT}). */
    public Set<Integer> claimedIds() {
        return Set.copyOf(claimed);
    }

    /** How many crystals were ever claimed since the last {@link #clear}. */
    public long claimedTotal() {
        return total;
    }

    public void clear() {
        unclaimed.clear();
        claimed.clear();
        seen.clear();
        total = 0;
    }
}
