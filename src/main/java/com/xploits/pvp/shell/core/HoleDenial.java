package com.xploits.pvp.shell.core;

import java.util.Optional;

/**
 * Denying the opponents their holes (surround++ spec §7.2): a hole within {@link #DENY_RADIUS} of an opponent, the
 * nearest to one first, that we can place into; never one next to us, which may be where we go. It replaces Meteor's
 * hole-filler when auto-pvp drives surround++. It does not stop an opponent building his own surround.
 */
public final class HoleDenial {
    public static final double DENY_RADIUS = 3.0;
    /** A hole this close to our feet block, horizontally, is ours to use: never filled. */
    static final int OWN_MARGIN = 1;

    private HoleDenial() {
    }

    public static Optional<Cell> choose(ThreatMap map) {
        ShellSnapshot s = map.snapshot();
        if (!s.settings().denyHoles() || s.hostileFeet().isEmpty()) return Optional.empty();
        Cell best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -ShellSnapshot.BELOW + 1; y <= ShellSnapshot.ABOVE - 1; y++) {
            for (int x = -ShellSnapshot.RADIUS + 1; x <= ShellSnapshot.RADIUS - 1; x++) {
                for (int z = -ShellSnapshot.RADIUS + 1; z <= ShellSnapshot.RADIUS - 1; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) <= OWN_MARGIN) continue;
                    Cell c = new Cell(x, y, z);
                    if (!Holes.isHole(s, c) || s.occupied(c) || !ShellPlanner.canPlace(map, c, false)) continue;
                    double distance = nearestOpponent(s, c);
                    if (distance <= DENY_RADIUS && distance < bestDistance) {
                        best = c;
                        bestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The horizontal distance from the hole to the nearest opponent standing within two blocks of its height. */
    private static double nearestOpponent(ShellSnapshot s, Cell c) {
        double nearest = Double.MAX_VALUE;
        Vec centre = c.centre();
        for (Vec feet : s.hostileFeet()) {
            if (Math.abs(feet.y() - c.y()) > 2) continue;
            nearest = Math.min(nearest, feet.horizontalDistance(centre));
        }
        return nearest;
    }
}
