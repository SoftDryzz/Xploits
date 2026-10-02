package com.xploits.restock.core;

/**
 * Whether a due trip may leave now (deferred m2, after ruling R32): {@link RestockTrip#mayLeave} must have held for
 * {@code leaveTicks} session ticks in a row. A player building by hand at an edge taps or switches movement keys, and a
 * single tick with none held is not the player standing: a trip started then would switch the printer off and stop at
 * its first step on the next key ({@code PLAYER_MOVED}), leaving the printer and restock off. Fed every session tick.
 */
public final class ReadyToLeave {
    private final int leaveTicks;
    private int held;

    public ReadyToLeave(int leaveTicks) {
        if (leaveTicks < 1) throw new IllegalArgumentException("leaveTicks " + leaveTicks);
        this.leaveTicks = leaveTicks;
    }

    /** One session tick with {@code mayLeave} as {@link RestockTrip#mayLeave} answers it now; true when ready. */
    public boolean tick(boolean mayLeave) {
        held = mayLeave ? Math.min(held + 1, leaveTicks) : 0;
        return held >= leaveTicks;
    }
}
