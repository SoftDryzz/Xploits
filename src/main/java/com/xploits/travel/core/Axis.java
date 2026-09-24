package com.xploits.travel.core;

/**
 * The eight highway axes of the XZ plane: the four cardinal ones and the four diagonals.
 *
 * <p>The diagonals are not a cosmetic extra: on an anarchy server they are half of the highways that
 * exist, and without them highway mode only serves half of the trips.
 *
 * <p><b>Each axis carries its unit vector</b>, and from that comes the decision that governs
 * everything else: {@code highway-distance} is <b>blocks travelled</b>, not blocks per coordinate. A
 * distance of N along {@link #X_PLUS_Z_PLUS} advances {@code N/√2} in X and as much in Z, and the
 * flight measures N, exactly the same as a distance of N along {@link #X_PLUS}. The setting means the
 * same on all eight axes, which is the only thing that lets you compare them without doing sums:
 * "20 000 blocks" is always the same stretch of fireworks, wherever it points.
 *
 * <p>That is why the pair of signs that identifies the axis is normalised in the constructor instead
 * of being written already normalised: that way the enum reads as what it is -{@code (1, 1)} is the
 * diagonal of X+ and Z+- and the normalisation, which is the real rule, appears once and not eight
 * times.
 *
 * <p><b>The names are the identifier Meteor saves to disk.</b> {@code EnumSetting.save} writes
 * {@code get().toString()} and {@code load} looks it up among the values, comparing {@code
 * toString()} again; if it does not find it, {@code parse} assigns nothing and the setting stays at
 * its default value. So renaming the four cardinal ones -or giving them a prettier {@code
 * toString()}- would silently change the axis for whoever had one saved. The four diagonals are
 * named following the same scheme, which is also the one that reads at a glance in the ClickGUI
 * dropdown: the dropdown paints {@code toString()} as it is, untouched.
 */
public enum Axis {
    X_PLUS(1, 0),
    X_MINUS(-1, 0),
    Z_PLUS(0, 1),
    Z_MINUS(0, -1),
    X_PLUS_Z_PLUS(1, 1),
    X_PLUS_Z_MINUS(1, -1),
    X_MINUS_Z_PLUS(-1, 1),
    X_MINUS_Z_MINUS(-1, -1);

    private final double unitX;
    private final double unitZ;

    /**
     * @param signX which way the axis goes in X: 1, 0 or -1
     * @param signZ which way the axis goes in Z: 1, 0 or -1
     */
    Axis(int signX, int signZ) {
        double length = Math.hypot(signX, signZ);
        this.unitX = signX / length;
        this.unitZ = signZ / length;
    }

    /**
     * The X component of the axis's unit vector. On the cardinal ones it is exactly 1, 0 or -1
     * -{@code hypot(1, 0)} is 1.0 with no rounding error-, so the four usual axes resolve to the same
     * point they resolved to before the diagonals existed.
     */
    public double unitX() {
        return unitX;
    }

    /** The Z component of the axis's unit vector. */
    public double unitZ() {
        return unitZ;
    }

    /** Whether the axis is one of the four diagonals, where the distance is split between X and Z. */
    public boolean isDiagonal() {
        return unitX != 0 && unitZ != 0;
    }
}
