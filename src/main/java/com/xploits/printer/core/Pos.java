package com.xploits.printer.core;

/**
 * A block position. Absolute and kept in memory only: the printer never prints, logs or writes one (printer spec §9,
 * "No coordinates anywhere").
 */
public record Pos(int x, int y, int z) {
    public Pos offset(Face face) {
        return new Pos(x + face.dx(), y + face.dy(), z + face.dz());
    }

    public Point center() {
        return new Point(x + 0.5, y + 0.5, z + 0.5);
    }

    /** The squared distance from {@code p} to this block's centre. */
    public double distanceSq(Point p) {
        double dx = x + 0.5 - p.x();
        double dy = y + 0.5 - p.y();
        double dz = z + 0.5 - p.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
