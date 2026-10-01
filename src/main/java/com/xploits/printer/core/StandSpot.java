package com.xploits.printer.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where to stand next (printer spec §5.7, N-M8): the lowest layer that still has actionable targets, then the feet
 * position nearest to the player from which one of them is reachable under §5.2's geometry. Both cells of the spot must be
 * schematic air or outside the placement, so the player never stands where a block goes. A spot the walk could not reach
 * is never offered again in this session; {@code unreachableSpots} of them in a row end the walk.
 */
public final class StandSpot {
    /** What the adapter measures of the world around a candidate. */
    public interface World {
        /** Two passable blocks over a full top face. */
        boolean standable(Pos feet);

        /** A face of this block may be clicked ({@link PhaseRules#support}). */
        boolean support(Pos block);
    }

    /** The standing eye height above the feet. */
    private static final double EYE = 1.62;
    /** Only the nearest actionable targets of the layer are tried, for a bounded cost. */
    private static final int TARGETS = 64;

    private final PrinterLimits limits;
    private final Set<Pos> unreachable = new HashSet<>();
    private int streak;

    public StandSpot(PrinterLimits limits) {
        this.limits = limits;
    }

    public Optional<Pos> next(BuildIndex index, Set<String> carried, boolean fixWrong, Point playerFeet, double reach,
                              World world) {
        double cappedReach = Math.min(reach, limits.maxReach());
        for (int layer : index.actionableLayers(carried, fixWrong)) {
            List<Pos> targets = new ArrayList<>();
            for (Pos t : index.actionableAt(layer, carried, fixWrong)) {
                if (index.status(t) != BuildIndex.Status.MISSING || hasSupport(t, world)) targets.add(t);
            }
            targets.sort(Comparator.<Pos>comparingDouble(t -> t.distanceSq(playerFeet))
                .thenComparingInt(Pos::x).thenComparingInt(Pos::z));
            Pos best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (Pos t : targets.subList(0, Math.min(TARGETS, targets.size()))) {
                boolean missing = index.status(t) == BuildIndex.Status.MISSING;
                for (int dy = -3; dy <= 1; dy++) {
                    for (int dx = -4; dx <= 4; dx++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            Pos feet = new Pos(t.x() + dx, t.y() + dy, t.z() + dz);
                            if (unreachable.contains(feet)) continue;
                            if (!free(index, feet) || !free(index, feet.offset(Face.UP))) continue;
                            // Standing on a wrong block protects it from breaking: the walk would loop.
                            if (index.status(feet.offset(Face.DOWN)) == BuildIndex.Status.WRONG) continue;
                            if (!world.standable(feet)) continue;
                            Point eye = new Point(feet.x() + 0.5, feet.y() + EYE, feet.z() + 0.5);
                            if (!reachable(t, missing, eye, cappedReach, world)) continue;
                            double d = new Point(feet.x() + 0.5, feet.y(), feet.z() + 0.5).distanceSq(playerFeet);
                            if (d < bestD || (d == bestD && before(feet, best))) {
                                bestD = d;
                                best = feet;
                            }
                        }
                    }
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /** A missing target can be clicked only from a supporting neighbour. */
    private static boolean hasSupport(Pos t, World world) {
        for (Face f : Face.values()) {
            if (world.support(t.offset(f))) return true;
        }
        return false;
    }

    public void unreachable(Pos spot) {
        unreachable.add(spot);
        streak++;
    }

    public void arrived() {
        streak = 0;
    }

    public boolean exhausted() {
        return streak >= limits.unreachableSpots();
    }

    public int streak() {
        return streak;
    }

    private static boolean free(BuildIndex index, Pos p) {
        BuildIndex.Status s = index.status(p);
        return s == BuildIndex.Status.AIR_TARGET || s == BuildIndex.Status.OUTSIDE;
    }

    private boolean reachable(Pos t, boolean missing, Point eye, double reach, World world) {
        for (Face f : Face.values()) {
            Pos block = missing ? t.offset(f) : t;
            Face side = missing ? f.opposite() : f;
            if (missing && !world.support(block)) continue;
            if (!Aim.facesEye(block, side, eye)) continue;
            if (Aim.hitPoint(block, side, eye, limits.hitMargin()).distance(eye) <= reach) return true;
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
