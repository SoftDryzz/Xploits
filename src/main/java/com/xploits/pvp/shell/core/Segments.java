package com.xploits.pvp.shell.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The cells a straight line crosses, in order (Amanatides and Woo's grid walk): how a crystal's ray to us is followed
 * through the blocks around us (surround++ spec §5.3).
 */
public final class Segments {
    /** More steps than any line inside the snapshot's box needs: a guard, never reached. */
    static final int MAX_STEPS = 64;

    private Segments() {
    }

    /** Every cell from the one {@code from} is in to the one {@code to} is in, both included, in the order crossed. */
    public static List<Cell> crossed(Vec from, Vec to) {
        int x = (int) Math.floor(from.x());
        int y = (int) Math.floor(from.y());
        int z = (int) Math.floor(from.z());
        int endX = (int) Math.floor(to.x());
        int endY = (int) Math.floor(to.y());
        int endZ = (int) Math.floor(to.z());
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);
        double tMaxX = firstCrossing(from.x(), dx);
        double tMaxY = firstCrossing(from.y(), dy);
        double tMaxZ = firstCrossing(from.z(), dz);
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell(x, y, z));
        for (int i = 0; i < MAX_STEPS && (x != endX || y != endY || z != endZ); i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            cells.add(new Cell(x, y, z));
        }
        return cells;
    }

    /** The fraction of the way along the line where it first crosses a block boundary on one axis. */
    private static double firstCrossing(double start, double delta) {
        if (delta > 0) return (Math.floor(start) + 1 - start) / delta;
        if (delta < 0) return (start - Math.floor(start)) / -delta;
        return Double.POSITIVE_INFINITY;
    }
}
