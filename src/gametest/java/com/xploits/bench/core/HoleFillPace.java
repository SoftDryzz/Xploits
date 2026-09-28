package com.xploits.bench.core;

import java.util.List;

/**
 * Task A2+ requirement 4 ("hole fill"): every {@value #HOLE_FILL_EVERY} ticks, the fight opponent fills the
 * nearest free 1x1 hole within {@value #HOLE_FILL_RADIUS} blocks of the real player that the player is not
 * standing in, so there is nowhere left to step into. Pure and deterministic: the adapter supplies the
 * distance of each free candidate hole (already filtered: free, in radius, not the player's own block), and
 * this class picks the nearest, ties keeping the first in the list — mirroring, but the opposite direction
 * of, A2's {@link CrystalAttackPace#chooseCell} (that one keeps the highest; a hole is filled nearest-first).
 */
public final class HoleFillPace {
    /** A hole is filled at most this often. */
    public static final int HOLE_FILL_EVERY = 40;
    /** Candidate holes farther than this from the player are ignored. */
    public static final double HOLE_FILL_RADIUS = 3;

    private HoleFillPace() {
    }

    /** Whether a hole should be filled at {@code sinceT0}: every {@value #HOLE_FILL_EVERY} ticks, never at T0. */
    public static boolean dueAt(int sinceT0) {
        return PeriodicTrigger.dueAt(sinceT0, HOLE_FILL_EVERY);
    }

    /**
     * The index of the nearest candidate hole in {@code distancesFromPlayer}; ties keep the first in the list.
     *
     * @throws IllegalArgumentException the list is empty: the caller must check for itself before calling this
     *                                  — there is nothing here to choose between
     */
    public static int nearestHole(List<Double> distancesFromPlayer) {
        if (distancesFromPlayer.isEmpty()) throw new IllegalArgumentException("no candidate holes to choose from");
        int best = 0;
        for (int i = 1; i < distancesFromPlayer.size(); i++) {
            if (distancesFromPlayer.get(i) < distancesFromPlayer.get(best)) best = i;
        }
        return best;
    }
}
