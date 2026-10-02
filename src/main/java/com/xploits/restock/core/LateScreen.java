package com.xploits.restock.core;

/**
 * The answer to restock's container click that comes only after the trip stopped waiting for it (rulings R12, R28,
 * R28b; deferred L79). Once the open gives up, a container screen that opens within {@code windowTicks} unpaused ticks,
 * while the player is still within the range at which the server keeps the clicked container's screen, is that answer:
 * it is remembered by its {@code syncId} and closed as soon as a close is allowed — even if the window has run out
 * meanwhile, as when it arrives while the player walks to the next source and the close waits for the walk to end. Only
 * that {@code syncId} is ever closed: once it is gone (the server or the player closed it) it is forgotten, and a screen
 * that opens after the window, or any other screen, never is. One per trip.
 */
public final class LateScreen {
    /** No screen: none shown, or nothing to close. */
    public static final int NONE = -1;

    private final int windowTicks;
    /** Restock's click left and no screen has answered it yet. */
    private boolean waiting;
    /** Unpaused ticks since the open stopped waiting for that answer. */
    private int ticks;
    /** The late answer's syncId, until it is closed or gone. */
    private int remembered = NONE;

    public LateScreen(int windowTicks) {
        if (windowTicks < 1) throw new IllegalArgumentException("windowTicks " + windowTicks);
        this.windowTicks = windowTicks;
    }

    /** Restock's click on a container left: a screen may answer it. */
    public void clicked() {
        waiting = true;
        ticks = 0;
    }

    /** The open adopted the screen {@code syncId} in time: that one is restock's own, and nothing is late. */
    public void adopted(int syncId) {
        waiting = false;
        if (remembered == syncId) remembered = NONE;
    }

    /**
     * One trip tick in which the open is not waiting for its screen (any phase but WAIT_SCREEN).
     *
     * @param shown    the syncId of the container screen shown now; {@link #NONE} when there is none, or it is the one
     *                 restock adopted
     * @param inRange  the player is within the range at which the server keeps the clicked container's screen open
     * @param paused   a guard pause this tick: it does not count towards the window
     * @param mayClose a close may go out now: standing still, nothing on the cursor, no pause, no container action yet
     * @return the syncId to close now, or {@link #NONE}
     */
    public int tick(int shown, boolean inRange, boolean paused, boolean mayClose) {
        if (remembered != NONE && shown != remembered) remembered = NONE;
        if (waiting) {
            if (!paused) ticks++;
            if (ticks > windowTicks || !inRange) {
                waiting = false;
            } else if (shown != NONE) {
                remembered = shown;
                waiting = false;
            }
        }
        if (remembered == NONE || !mayClose) return NONE;
        int close = remembered;
        remembered = NONE;
        return close;
    }
}
