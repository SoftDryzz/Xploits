package com.xploits.pvp.crystal.core;

/**
 * Where your feet are (your entity position), for {@link HurtWindow}'s movement guard only. It never leaves the
 * core and is never shown: {@link #toString()} carries no numbers.
 *
 * @param x the x coordinate; with {@code y} and {@code z} all finite, or all NaN for {@link #UNKNOWN}
 */
public record Feet(double x, double y, double z) {
    /** A position that could not be read: the hurt cooldown is then never credited. */
    public static final Feet UNKNOWN = new Feet(Double.NaN, Double.NaN, Double.NaN);

    public Feet {
        boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        boolean unknown = Double.isNaN(x) && Double.isNaN(y) && Double.isNaN(z);
        if (!finite && !unknown) throw new IllegalArgumentException("feet not finite");
    }

    /** Whether it was read. */
    public boolean known() {
        return Double.isFinite(x);
    }

    /**
     * Whether {@code other} is more than {@code limit} away from here, horizontally (on x and z together) or
     * vertically. An unknown position on either side counts as moved.
     */
    public boolean movedMoreThan(Feet other, double limit) {
        if (!known() || !other.known()) return true;
        double dx = other.x - x;
        double dz = other.z - z;
        return dx * dx + dz * dz > limit * limit || Math.abs(other.y - y) > limit;
    }

    @Override
    public String toString() {
        return known() ? "Feet[known]" : "Feet[unknown]";
    }
}
