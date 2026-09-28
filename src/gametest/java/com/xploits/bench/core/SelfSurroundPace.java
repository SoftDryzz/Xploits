package com.xploits.bench.core;

import java.util.List;

/**
 * Task A2+ requirement 3 ("self-surround"): once our crystals start hurting the opponent, it fills the four
 * horizontal sides of its own feet with obsidian, at most {@value #SURROUND_BLOCKS_PER_TICK} block a tick,
 * re-placing a broken one — never the block below it. The four sides are checked in a fixed order (the
 * adapter's own list); this class only picks which one (if any) needs a block this tick: the first, in that
 * order, that is not currently obsidian. Pure and deterministic.
 */
public final class SelfSurroundPace {
    /** How many of the (up to four) missing sides get a block in one tick. */
    public static final int SURROUND_BLOCKS_PER_TICK = 1;

    private SelfSurroundPace() {
    }

    /**
     * The index, in {@code present}'s order, of the first side that is not yet obsidian (missing, or broken
     * and not yet re-placed); -1 when every side already is.
     */
    public static int nextMissingSide(List<Boolean> present) {
        for (int i = 0; i < present.size(); i++) {
            if (!present.get(i)) return i;
        }
        return -1;
    }
}
