package com.xploits.sweep.core;

/**
 * One lane of the sweep: a straight stretch in Nether block coordinates, from one end to the other.
 * The firework cost is computed by adding up {@link #lengthInBlocks()} of every lane before takeoff,
 * so this length is what decides whether the player flies amply supplied or runs short halfway
 * through the sweep.
 */
public record Lane(double fromX, double fromZ, double toX, double toZ) {
    /** Euclidean distance between the two ends of the lane. */
    public double lengthInBlocks() {
        return Math.hypot(toX - fromX, toZ - fromZ);
    }
}
