package com.xploits.travel.core;

/**
 * The stall watch (AutoTravel spec §8): decides when a trip has stopped making progress and has to be
 * cut. Baritone does not report how it is doing, so the only thing observable from outside is whether
 * the distance to the waypoint drops; this class is everything that can be reasoned about that
 * without touching Minecraft, and that is why it lives in the core and is tested whole without
 * starting the game.
 *
 * <p><b>It counts ticks, not wall-clock milliseconds</b>, like the rest of the core: a test cannot
 * wait thirty real seconds, and the flight is observed once per tick anyway.
 *
 * <p><b>The counter is tied to the waypoint it watches.</b> Moving on to the next waypoint makes the
 * distance jump up all at once -from the arrival margin to the thousands of blocks of the next one-,
 * and comparing that jump with the previous waypoint's minimum distance would read it as "I am not
 * getting closer" for thirty seconds in a row and cut a trip that is going perfectly. That is why
 * {@link #tick(int, double)} receives the waypoint's index and resets itself only when it changes:
 * the adapter cannot forget to reset it, because it is not the one that does it.
 */
public final class StallWatch {
    /** The client's ticks per second, to turn the limit into seconds in the messages. */
    public static final int TICKS_PER_SECOND = 20;

    private final int limitTicks;
    private final double epsilon;

    /** The waypoint being watched, or -1 if none has been observed yet. */
    private int watchedWaypoint = -1;

    /** The shortest distance observed to that waypoint. Infinity while there is none. */
    private double closestDistance = Double.POSITIVE_INFINITY;

    private int ticksWithoutProgress;

    /**
     * @param limitTicks how many ticks in a row without getting closer make it cut. At least one
     * @param epsilon    how much the distance has to drop to count as progress, in blocks. Without
     *                   this margin, the one-block sway of the flight itself would rearm the counter
     *                   forever and the watch would never cut; getting half a block closer in thirty
     *                   seconds is not making progress
     */
    public StallWatch(int limitTicks, double epsilon) {
        if (limitTicks < 1) {
            throw new IllegalArgumentException("the stall limit has to be at least one tick: " + limitTicks);
        }
        if (!(epsilon >= 0) || Double.isInfinite(epsilon)) {
            throw new IllegalArgumentException("the progress epsilon has to be a finite non-negative number: " + epsilon);
        }
        this.limitTicks = limitTicks;
        this.epsilon = epsilon;
    }

    /** The same watch expressed in seconds, which is how the spec writes it. */
    public static StallWatch ofSeconds(double seconds, double epsilon) {
        return new StallWatch((int) Math.round(seconds * TICKS_PER_SECOND), epsilon);
    }

    /**
     * Observes one tick of flight and says whether to cut.
     *
     * @param waypointIndex the waypoint being headed to now. If it is a different one from the
     *                      previous tick's, the watch starts from zero: the distance to the new
     *                      waypoint is never compared with the old one's
     * @param distance      the distance left to that waypoint, in blocks
     * @return {@code true} if the limit's ticks have passed without getting closer
     */
    public boolean tick(int waypointIndex, double distance) {
        if (waypointIndex != watchedWaypoint) {
            watchedWaypoint = waypointIndex;
            forget();
        }

        // The first tick of each waypoint always lands here -any distance is less than infinity-, so
        // it sets the reference and never counts as a stall.
        if (distance < closestDistance - epsilon) {
            closestDistance = distance;
            ticksWithoutProgress = 0;
            return false;
        }

        ticksWithoutProgress++;
        return ticksWithoutProgress >= limitTicks;
    }

    /** Goes back to the freshly started state. For the start and the end of a trip. */
    public void reset() {
        watchedWaypoint = -1;
        forget();
    }

    /** How many ticks in a row it has gone without getting closer to the watched waypoint. */
    public int ticksWithoutProgress() {
        return ticksWithoutProgress;
    }

    /** The limit, in seconds, so that it can be named in the warning. */
    public long limitSeconds() {
        return Math.round((double) limitTicks / TICKS_PER_SECOND);
    }

    private void forget() {
        closestDistance = Double.POSITIVE_INFINITY;
        ticksWithoutProgress = 0;
    }
}
