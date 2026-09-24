package com.xploits.travel.core;

/**
 * A point on the XZ plane, with no height: Baritone works out the Y by itself when chasing a
 * two-coordinate {@code goal}.
 */
public record Waypoint(double x, double z) {
    /** Euclidean distance on the XZ plane to {@code other}. */
    public double distanceTo(Waypoint other) {
        double dx = other.x() - x;
        double dz = other.z() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
