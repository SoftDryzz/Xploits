package com.xploits.printer.core;

/** A point in the world, in blocks. In memory only, like {@link Pos}. */
public record Point(double x, double y, double z) {
    public double distanceSq(Point o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Point o) {
        return Math.sqrt(distanceSq(o));
    }
}
