package com.xploits.travel.core;

/**
 * Where the player wants to go (AutoTravel spec). There are three ways of asking for it:
 *
 * <ul>
 *   <li>{@link #coordinates(double, double)}: an absolute point in the world, in X and Z.</li>
 *   <li>{@link #relative(double, double)}: an <b>offset</b> in X and in Z from where the trip
 *       starts. Entering 5000 and -3000 is "move 5000 in X and -3000 in Z from here".</li>
 *   <li>{@link #highway(Axis, double)}: a distance to travel along an axis, measured from the trip's
 *       starting point -not from the world origin-, as befits asking for "so many blocks along the
 *       +X highway" without knowing the exact coordinates.</li>
 * </ul>
 *
 * <p>Resolving a destination is pure arithmetic -a starting point and a few numbers-, so it lives
 * here and is tested without starting the game; the adapter only supplies the origin.
 *
 * <p>{@link Kind#HIGHWAY} is the mark that tells highway mode apart: in it the decoy is rejected
 * instead of downgraded, because it would take the player out of the corridor. {@link #highway()} is
 * what {@link RoutePlanner} looks at, and it is still a yes-or-no question.
 *
 * <p><b>The three ways are three different fields and not one reinterpreted</b>, and that is also
 * deliberate upstream, in the module's settings: if {@code x}/{@code z} served both for the absolute
 * coordinates and for the offset, switching mode would turn a far destination already configured into
 * a huge offset from wherever you are, without touching a single number. The resulting trip would look
 * nothing like the one either meaning asked for.
 *
 * <p><b>An offset of (0, 0) is a destination equal to the origin</b>, and that is not rejected: it
 * falls into the same path as a coordinates destination that matches the player's position, or a
 * highway distance of 0, and the planner already returns a one-waypoint route there -the starting
 * point itself-. It is not a silent downgrade: a zero-block trip is exactly what was asked for,
 * delivered as it is, and there is no pattern being drawn straight behind anyone's back. What would
 * be rejected is delivering something different while keeping quiet about it.
 */
public record Destination(Kind kind, double x, double z, Axis axis, double distance) {
    /** The three ways of asking for a destination, as the core's vocabulary. */
    public enum Kind {
        /** An absolute point in the world. */
        COORDINATES,
        /** An offset in X and in Z from the trip's starting point. */
        RELATIVE,
        /** A highway axis and a distance to travel along it. */
        HIGHWAY
    }

    public Destination {
        if (kind == null) {
            throw new IllegalArgumentException("a destination needs a kind: kind cannot be null");
        }
        if (kind == Kind.HIGHWAY && axis == null) {
            throw new IllegalArgumentException("a highway destination needs an axis: axis cannot be null");
        }
    }

    /** An absolute point in the world. */
    public static Destination coordinates(double x, double z) {
        return new Destination(Kind.COORDINATES, x, z, null, 0);
    }

    /** An offset in X and in Z from the trip's starting point. */
    public static Destination relative(double offsetX, double offsetZ) {
        return new Destination(Kind.RELATIVE, offsetX, offsetZ, null, 0);
    }

    /** A distance to travel along the given axis, from the trip's starting point. */
    public static Destination highway(Axis axis, double distance) {
        return new Destination(Kind.HIGHWAY, 0, 0, axis, distance);
    }

    /**
     * Whether the destination is tied to a highway axis, which is what forces the pattern to stay
     * inside the corridor and what forbids the decoy (spec §4.2).
     */
    public boolean highway() {
        return kind == Kind.HIGHWAY;
    }

    /**
     * Resolves this destination to a concrete point, given the trip's origin.
     *
     * <p>On a highway the point is {@code origin + unit(axis) * distance}. With the unit vector -and
     * not the bare pair of signs- the distance is <b>blocks travelled</b> on all eight axes: along a
     * diagonal, N blocks advance {@code N/√2} in each coordinate and the flight measures N. The four
     * cardinal ones have an exact unit vector of (±1, 0) or (0, ±1), so they resolve to the same
     * point as always.
     */
    public Waypoint resolve(Waypoint origin) {
        return switch (kind) {
            case COORDINATES -> new Waypoint(x, z);
            case RELATIVE -> new Waypoint(origin.x() + x, origin.z() + z);
            case HIGHWAY -> new Waypoint(origin.x() + axis.unitX() * distance,
                origin.z() + axis.unitZ() * distance);
        };
    }
}
