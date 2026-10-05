package com.xploits.restock.core;

/**
 * What a stop leaves of phase B at the build (restock spec §3 "never a shulker left behind silently"; owner ruling R42),
 * distances only: how many boxes restock set down still stand and how far the nearest is (−1: none), how far the broken
 * one lies on the ground (−1: not on the ground), whether the broken one is gone, whether the world could not be checked,
 * how many borrowed boxes the player still carries, and whether a box said to stand may have just broken — the dig's
 * STOP went out and the server has not answered yet (M17).
 */
public record ShulkersLeft(int standing, long nearestStanding, long onGround, boolean lost, boolean unchecked,
                           int borrowed, boolean breaking) {
    public static final ShulkersLeft NONE = new ShulkersLeft(0, -1, -1, false, false, 0);
    public static final ShulkersLeft UNCHECKED = new ShulkersLeft(0, -1, -1, false, true, 0);

    public ShulkersLeft {
        if (standing < 0 || borrowed < 0) throw new IllegalArgumentException("a count below 0");
        if ((standing > 0) != (nearestStanding >= 0)) throw new IllegalArgumentException("a standing box has a distance");
        if (lost && onGround >= 0) throw new IllegalArgumentException("a box on the ground is not lost");
        if (breaking && standing == 0) throw new IllegalArgumentException("only a standing box may be breaking");
    }

    /** Nothing just broken. */
    public ShulkersLeft(int standing, long nearestStanding, long onGround, boolean lost, boolean unchecked,
                        int borrowed) {
        this(standing, nearestStanding, onGround, lost, unchecked, borrowed, false);
    }

    public ShulkersLeft withBorrowed(int n) {
        return new ShulkersLeft(standing, nearestStanding, onGround, lost, unchecked, n, breaking);
    }
}
