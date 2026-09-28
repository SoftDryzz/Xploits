package com.xploits.bench.core;

/**
 * Task A2+ requirement 1 ("autobreak"): whether the fight opponent hits a given end crystal this tick — any
 * crystal that is not its own and stands within {@value #AUTOBREAK_RANGE} of it, the same 4.5-block place/break
 * range documented elsewhere in the bench (e.g. {@code Strafe}, {@code Autobreak}'s own server-side wiring).
 * Pure and deterministic: the adapter supplies the distance and whether the crystal is the opponent's own (a
 * crystal it may be mid-cycle on placing for its own attack, e.g. A2's {@code CrystalAttack}).
 */
public final class AutobreakRange {
    /** Meteor's place and break range: crystals farther than this from the opponent are out of its reach. */
    public static final double AUTOBREAK_RANGE = 4.5;

    private AutobreakRange() {
    }

    /** Whether a crystal {@code distance} blocks from the opponent, not its own, should be broken this tick. */
    public static boolean shouldBreak(double distance, boolean ownCrystal) {
        return !ownCrystal && distance <= AUTOBREAK_RANGE;
    }
}
