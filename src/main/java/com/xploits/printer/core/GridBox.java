package com.xploits.printer.core;

/** An axis-aligned box of blocks, both corners included. */
public record GridBox(Pos min, Pos max) {
    public GridBox {
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("a box's min corner is above its max corner");
        }
    }

    public static GridBox of(Pos a, Pos b) {
        return new GridBox(new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
            new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
    }

    public boolean contains(Pos p) {
        return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
            && p.z() >= min.z() && p.z() <= max.z();
    }

    public boolean intersects(GridBox o) {
        return min.x() <= o.max.x() && o.min.x() <= max.x() && min.y() <= o.max.y() && o.min.y() <= max.y()
            && min.z() <= o.max.z() && o.min.z() <= max.z();
    }

    public int sizeX() {
        return max.x() - min.x() + 1;
    }

    public int sizeY() {
        return max.y() - min.y() + 1;
    }

    public int sizeZ() {
        return max.z() - min.z() + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }
}
