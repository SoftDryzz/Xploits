package com.xploits.pvp.shell.core;

/**
 * A point relative to the corner (the lowest x, y and z) of the player's feet block, in blocks (surround++ spec §4.1):
 * never an absolute position.
 */
public record Vec(double x, double y, double z) {
    public Vec plus(double dx, double dy, double dz) {
        return new Vec(x + dx, y + dy, z + dz);
    }

    public double squaredDistance(Vec o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec o) {
        return Math.sqrt(squaredDistance(o));
    }

    /** The distance on the ground, height left out. */
    public double horizontalDistance(Vec o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The cell this point is in. */
    public Cell cell() {
        return new Cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
