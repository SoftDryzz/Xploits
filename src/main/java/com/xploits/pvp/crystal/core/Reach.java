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
     * Meteor's {@code isOutOfRange} (lines 1164-1172): when the eye raycast does not end on the crystal's own
     * block the spot is behind a wall and the walls range applies, otherwise the range; within means
     * {@code PlayerUtils.isWithin}, a squared distance from your feet {@code <= r * r}.
     *
     * @param behindWall      the eye raycast did not end on the block
     * @param squaredDistance from your feet to the crystal position
     * @param range           place-range or break-range
     * @param wallsRange      place-walls-range or break-walls-range
     */
    public static boolean outOfRange(boolean behindWall, double squaredDistance, double range, double wallsRange) {
        double r = behindWall ? wallsRange : range;
        return !(squaredDistance <= r * r);
    }
}
