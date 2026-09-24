package com.xploits.console.core;

import java.util.OptionalLong;

/**
 * The messages that did not fit in the writer's queue. The first one is announced in the game, once;
 * the rest are counted and written as a {@code P} entry when the queue drains.
 *
 * <p>It is called from two threads (the game thread drops, the writer drains), hence the
 * {@code synchronized}.
 */
public final class DroppedMessages {
    private long count;
    private boolean announced;

    /** Counts one. Returns true only the first time: that is when the player must be told. */
    public synchronized boolean recordDrop() {
        count++;
        if (announced) return false;
        announced = true;
        return true;
    }

    /** If some were lost and not yet recorded, how many, and resets to zero. */
    public synchronized OptionalLong drain() {
        if (count == 0) return OptionalLong.empty();
        long n = count;
        count = 0;
        return OptionalLong.of(n);
    }
}
