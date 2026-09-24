package com.xploits.console.core;

/**
 * Detects lost entries: within a game session, {@code seq} grows one by one. Going backwards is a read
 * error, not a gap, and it is reported loudly.
 */
public final class SequenceTracker {
    private String session;
    private long last;

    /** How many entries are missing between the previous one of the same session and this one. A new session starts from zero. */
    public long gap(String session, long seq) {
        if (!session.equals(this.session)) {
            this.session = session;
            last = seq;
            return 0;
        }
        if (seq <= last) {
            throw new IllegalStateException("entry out of order: " + seq + " arrives after " + last);
        }
        long lost = seq - last - 1;
        last = seq;
        return lost;
    }
}
