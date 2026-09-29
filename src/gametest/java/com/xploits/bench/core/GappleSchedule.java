package com.xploits.bench.core;

/**
 * Task A1 requirement 4 (gapple model): {@value #DELAY_TICKS} ticks after a totem pop — the 1.6 s an
 * enchanted golden apple takes to eat — the bench applies its effects to that player, server-side
 * (documented simplification: it does not tie up the eater's hands for those ticks). One instance tracks
 * one player: {@link #pop} arms the delay from that tick, re-arming it from scratch on a later pop while
 * one is still pending (a pop again reschedules, it does not stack); {@link #death} cancels whatever is
 * pending, since a dead player eats nothing. Pure and deterministic.
 */
public final class GappleSchedule {
    /** Ticks after a pop the effects become due: the enchanted golden apple's 1.6 s eat, in ticks. */
    public static final int DELAY_TICKS = 32;

    /** The tick the effects are due at, or -1 while nothing is pending. */
    private int dueAtTick = -1;

    /** A totem popped at {@code tick}: the effects become due {@value #DELAY_TICKS} ticks after it. */
    public void pop(int tick) {
        dueAtTick = tick + DELAY_TICKS;
    }

    /** Cancels whatever is pending: a dead player eats nothing. */
    public void death() {
        dueAtTick = -1;
    }

    /** Whether a pop's effects are still waiting to fire. */
    public boolean pending() {
        return dueAtTick >= 0;
    }

    /**
     * Whether the effects are due at {@code tick} or later; consumes the pending schedule so they fire
     * exactly once per pop, even if the caller's own tick loop skipped the exact due tick.
     */
    public boolean due(int tick) {
        if (dueAtTick >= 0 && tick >= dueAtTick) {
            dueAtTick = -1;
            return true;
        }
        return false;
    }
}
