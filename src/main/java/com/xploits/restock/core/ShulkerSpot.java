package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.BlockFacts;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.PhaseRules;
import com.xploits.printer.core.PlacePlanner;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where to set a shulker box down at the build (restock spec §3 "Shulkers at the build"): a cell outside every box of the
 * selected placement, empty — air, or a block a placement replaces (P20) — with the cell above it empty and outside the
 * build too, so its lid opens ({@code ShulkerBoxBlock.canOpen}); on a block a click places against and never uses
 * ({@link #support}, ruling R27); with no fluid, fire or cactus in it or next to it, which would burn the drop or carry it
 * off ({@link #safe}); never where the player's collision box would overlap the box or its lid (P16: the box, not the
 * feet block); never a cell tried already in this unpacking (P19). The click goes on the support's top face — the box
 * then faces up — at the point of that face nearest the eye, kept {@code margin} from its edges, within {@code reach},
 * the face turned to the eye and the vanilla ray with exactly that rotation returning it. The nearest click wins; ties
 * by x, then y, then z.
 */
public final class ShulkerSpot {
    /** The player's collision box: 0.6 wide, 1.8 tall. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    /** Cells this many blocks around the feet block, from one below it to one above it, are considered. */
    private static final int AROUND = 3;
    /** Blocks that destroy a dropped item: fire burns it, cactus breaks it (fluids, lava included, are checked apart). */
    static final Set<String> HAZARDS = Set.of("minecraft:fire", "minecraft:soul_fire", "minecraft:lava",
        "minecraft:cactus");

    /** What the adapter measures around the player. */
    public interface World {
        /** Air, or a block a placement replaces, with no collision and no fluid, where the box may be placed; loaded. */
        boolean empty(Pos cell);

        /** {@link #support(BlockFacts, boolean)} of the block there; loaded. */
        boolean support(Pos block);

        /** {@link #safe(BlockFacts)} of the block there; false when not loaded. */
        boolean safe(Pos block);
    }

    /** The cell, the block under it that is clicked, the hit point on that block's top and the rotation to it. */
    public record Choice(Pos cell, Pos support, Point hit, Aim.Rotation rotation) {
        public Choice {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(hit, "hit");
            Objects.requireNonNull(rotation, "rotation");
        }

        /** Never a position ({@link HiddenPositions}). */
        @Override
        public String toString() {
            return "Choice[cell=" + HiddenPositions.HIDDEN + ", support=" + HiddenPositions.HIDDEN + ", hit="
                + HiddenPositions.HIDDEN + ", rotation=" + rotation + "]";
        }
    }

    private ShulkerSpot() {
    }

    /**
     * Ruling R27 for the click that sets the box down: a block a click places against and never uses —
     * {@code PhaseRules.support} (not air, a fluid or replaceable; not interactive: no respawn anchor, bed, barrel,
     * crafting table, door, lever…) — with no block entity, and a top that is a full square.
     */
    public static boolean support(BlockFacts block, boolean fullTopSquare) {
        return fullTopSquare && !block.blockEntity() && PhaseRules.support(block);
    }

    /** A block a drop is safe in or next to: no fluid (water carries it off, lava burns it), no fire, no cactus. */
    public static boolean safe(BlockFacts block) {
        return !block.fluid() && !HAZARDS.contains(block.id());
    }

    public static Optional<Choice> choose(List<GridBox> boxes, Point feet, Point eye, float yaw, Set<Pos> tried,
                                          double reach, double margin, World world, PlacePlanner.RayOracle oracle) {
        int fx = (int) Math.floor(feet.x());
        int fy = (int) Math.floor(feet.y());
        int fz = (int) Math.floor(feet.z());
        Choice best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -AROUND; dx <= AROUND; dx++) {
                for (int dz = -AROUND; dz <= AROUND; dz++) {
                    Pos cell = new Pos(fx + dx, fy + dy, fz + dz);
                    Pos lid = cell.offset(Face.UP);
                    Pos support = cell.offset(Face.DOWN);
                    if (tried.contains(cell) || inside(boxes, cell) || inside(boxes, lid)) continue;
                    if (overlapsBody(cell, feet)) continue;
                    if (!world.empty(cell) || !world.empty(lid) || !world.support(support)) continue;
                    if (!safeAround(cell, world)) continue;
                    if (!Aim.facesEye(support, Face.UP, eye)) continue;
                    Point hit = Aim.hitPoint(support, Face.UP, eye, margin);
                    double d = eye.distance(hit);
                    if (d > reach) continue;
                    if (best != null && (d > bestD || (d == bestD && !before(cell, best.cell())))) continue;
                    Aim.Rotation r = Aim.rotation(eye, hit, yaw);
                    if (!oracle.sees(support, Face.UP, r)) continue;
                    best = new Choice(cell, support, hit, r);
                    bestD = d;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean inside(List<GridBox> boxes, Pos p) {
        for (GridBox b : boxes) {
            if (b.contains(p)) return true;
        }
        return false;
    }

    /** The cell and its six neighbours (the support and the lid among them) hold nothing that destroys or moves a drop. */
    private static boolean safeAround(Pos cell, World world) {
        if (!world.safe(cell)) return false;
        for (Face f : Face.values()) {
            if (!world.safe(cell.offset(f))) return false;
        }
        return true;
    }

    /** The cell and its lid (two blocks tall) against the player's collision box standing at {@code feet}. */
    private static boolean overlapsBody(Pos cell, Point feet) {
        return cell.x() < feet.x() + HALF_WIDTH && cell.x() + 1 > feet.x() - HALF_WIDTH
            && cell.y() < feet.y() + HEIGHT && cell.y() + 2 > feet.y()
            && cell.z() < feet.z() + HALF_WIDTH && cell.z() + 1 > feet.z() - HALF_WIDTH;
    }

    private static boolean before(Pos a, Pos b) {
        if (a.x() != b.x()) return a.x() < b.x();
        if (a.y() != b.y()) return a.y() < b.y();
        return a.z() < b.z();
    }
}
