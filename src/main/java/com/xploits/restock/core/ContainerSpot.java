package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.Optional;

/**
 * Where to stand to open a container restock only knows from stash-keeper (restock spec §3 "The trip": approach with
 * {@code #goto x z}, choose once loaded): two passable blocks over a full top face, four blocks around the container at
 * most and from three below to one above it, from which a face of the container that is not covered by another block
 * looks at the eye and has its hit point within reach. The nearest to the player wins; ties by y, x, z. Never the
 * container's own cell nor the one under it. The raycast at the container itself is made when the player is there.
 * <p>
 * The search is the same as {@code com.xploits.printer.core.StandSpot}'s (candidate box, eye height, tie-break, reach
 * loop), kept separate while that core is frozen; a geometry fix in one must be mirrored in the other.
 */
public final class ContainerSpot {
    /** What the adapter measures around the container. */
    public interface World {
        /** Two passable blocks over a full top face, both loaded. */
        boolean standable(Pos feet);

        /** Nothing with a collision shape: the container's face next to it can be seen. */
        boolean open(Pos block);
    }

    /** The standing eye height above the feet. */
    private static final double EYE = 1.62;

    private ContainerSpot() {
    }

    public static Optional<Pos> choose(Pos container, Point playerFeet, double reach, double margin, World world) {
        Pos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int dy = -3; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Pos feet = new Pos(container.x() + dx, container.y() + dy, container.z() + dz);
                    if (feet.equals(container) || feet.offset(Face.UP).equals(container)) continue;
                    if (!world.standable(feet)) continue;
                    Point eye = new Point(feet.x() + 0.5, feet.y() + EYE, feet.z() + 0.5);
                    if (!reachable(container, eye, reach, margin, world)) continue;
                    double d = new Point(feet.x() + 0.5, feet.y(), feet.z() + 0.5).distanceSq(playerFeet);
                    if (d < bestD || (d == bestD && before(feet, best))) {
                        bestD = d;
                        best = feet;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** An open face of {@code container} looks at {@code eye} with its hit point within {@code reach}. */
    public static boolean reachable(Pos container, Point eye, double reach, double margin, World world) {
        for (Face f : Face.values()) {
            if (!world.open(container.offset(f))) continue;
            if (!Aim.facesEye(container, f, eye)) continue;
            if (Aim.hitPoint(container, f, eye, margin).distance(eye) <= reach) return true;
        }
        return false;
    }

    private static boolean before(Pos a, Pos b) {
        if (b == null) return true;
        if (a.y() != b.y()) return a.y() < b.y();
        if (a.x() != b.x()) return a.x() < b.x();
        return a.z() < b.z();
    }
}
