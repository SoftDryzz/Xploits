package com.xploits.pvp.shell.core;

/**
 * Our own hurt window (surround++ spec §6.4; the rule {@code TargetWindows} documents, read in the 1.21.11 jar): for 10
 * ticks after a full hit the server ignores a hit whose raw damage is not above the last one's. Only a crystal
 * explosion whose damage we measured opens one here; any other hit's size is unknown.
 *
 * @param ticksSince ticks since that hit's packet reached us, or -1 for no window
 * @param damage     the health it took, after armour
 */
public record HurtWindow(int ticksSince, double damage) {
    public static final int WINDOW_TICKS = 10;
    /** A crystal must be this much below the last hit to count as swallowed: our prediction is not the server's. */
    public static final double MARGIN = 0.5;
    public static final HurtWindow NONE = new HurtWindow(-1, 0);

    public HurtWindow {
        if (ticksSince < -1) throw new IllegalArgumentException("ticks since " + ticksSince);
        if (!Double.isFinite(damage) || damage < 0) throw new IllegalArgumentException("damage " + damage);
    }

    /**
     * Whether a crystal dealing {@code damage} to us, attacked now, explodes inside the window. The window started when
     * the last hit left the server, half a round trip before its packet reached us, and our attack takes the other half
     * to get there: the whole round trip counts against what is left of it.
     */
    public boolean swallows(double damage, int pingTicks) {
        return ticksSince >= 0 && ticksSince + pingTicks < WINDOW_TICKS && damage <= this.damage - MARGIN;
    }
}
