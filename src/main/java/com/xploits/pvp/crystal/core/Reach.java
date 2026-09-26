package com.xploits.pvp.crystal.core;

/**
 * Meteor's range checks, with the same arithmetic so every boundary is Meteor's own: squared distances
 * against the squared setting, never a square root.
 */
public final class Reach {
    private Reach() {}

    /**
     * Whether a player is close enough to be a target (CrystalAura line 1250: left out when
     * {@code squaredDistanceTo(player) > target-range * target-range}).
     */
    public static boolean inTargetRange(double squaredDistance, double targetRange) {
        return !(squaredDistance > targetRange * targetRange);
    }

    /**
     * The range Meteor's {@code isOutOfRange} measures against (lines 1164-1172): the place ranges for a spot
     * (line 949), the break ranges for a crystal (line 807); of the two, the walls range when the eye raycast
     * does not end on the crystal's own block (the spot is behind a wall), the range otherwise.
     *
     * @param placing    a spot to place on, not a crystal to break
     * @param behindWall the eye raycast did not end on the block
     */
    public static double rangeFor(boolean placing, boolean behindWall, double placeRange, double placeWallsRange,
                                  double breakRange, double breakWallsRange) {
        if (placing) return behindWall ? placeWallsRange : placeRange;
        return behindWall ? breakWallsRange : breakRange;
    }

    /**
     * Beyond {@code range}: not {@code PlayerUtils.isWithin}, which is a squared distance from your feet
     * {@code <= r * r} (lines 1170-1171).
     *
     * @param squaredDistance from your feet to the crystal position
     * @param range           from {@link #rangeFor}
     */
    public static boolean outOfRange(double squaredDistance, double range) {
        return !(squaredDistance <= range * range);
    }
}
