package com.xploits.bench.core;

/**
 * Task A2+ requirement 2 ("spot blocking"): the cadence and reach of the fight opponent placing obsidian on
 * the free spot next to it that a crystal would hurt it most from — the spot our aura would most likely use
 * next. The spot itself is chosen the same way A2's {@link CrystalAttackPace#chooseCell} already does (highest
 * raw damage, ties keep the first): the adapter measures each free candidate cell and reuses that function
 * directly, so this class only names the cadence and the reach. Pure and deterministic.
 */
public final class SpotBlockPace {
    /** A block is placed at most this often. */
    public static final int BLOCK_EVERY = 20;
    /** Candidate spots farther than this from the opponent are out of its reach (Meteor's place range). */
    public static final double REACH = 4.5;

    private SpotBlockPace() {
    }

    /** Whether a block should be placed at {@code sinceT0}: every {@value #BLOCK_EVERY} ticks, never at T0. */
    public static boolean dueAt(int sinceT0) {
        return PeriodicTrigger.dueAt(sinceT0, BLOCK_EVERY);
    }
}
