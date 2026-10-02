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
 * Where to set a shulker box down at the build (restock spec §3 "Shulkers at the build"): a cell outside every box of
 * the selected placement, empty (air, or a block a placement replaces: P20), with the cell above it empty and outside
 * the build too, so its lid opens ({@code ShulkerBoxBlock.canOpen}); on a block a click places against and never uses
 * ({@link #support}, ruling R27) that is also a {@link World#floor}; where the drop, which drifts up to about 1.5
 * blocks (and slides further on ice), cannot burn, be pulled in or carried off, or fall (ruling R51: {@link #safe}
 * over the 3 x 3 columns around the cell, support's level to the lid's; every neighbour column at the cell's level is
 * a full block that stops the drift, or lets the drop pass with a floor under it; rulings R56 to R58); never where the
 * player's collision box would overlap the box or its lid (P16: the box, not the feet block); never a cell tried
 * already in this unpacking (P19). The click goes on the support's top face (the box then faces up) at the point of
 * that face nearest the eye, kept {@code margin} from its edges, within {@code reach}, the face turned to the eye and
 * the vanilla ray with exactly that rotation returning it. The nearest click wins; ties by x, then y, then z.
 */
public final class ShulkerSpot {
    /** The player's collision box: 0.6 wide, 1.8 tall. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    /** Cells this many blocks around the feet block, from one below it to one above it, are considered. */
    private static final int AROUND = 3;
    /**
     * Blocks that destroy, pull in or carry off a dropped item (ruling R51): fire, lava and a lava cauldron burn it,
     * cactus breaks it, a hopper takes it from above, a portal or the end gateway sends it away. Fluids are checked apart.
     * Magma and campfires only hurt living entities and are allowed.
     */
    private static final Set<String> HAZARDS = Set.of("minecraft:fire", "minecraft:soul_fire", "minecraft:lava",
        "minecraft:cactus", "minecraft:lava_cauldron", "minecraft:hopper", "minecraft:nether_portal",
        "minecraft:end_portal", "minecraft:end_gateway");

    /** What the adapter measures around the player. */
    public interface World {
        /** Air, or a block a placement replaces, with no collision and no fluid, where the box may be placed; loaded. */
        boolean empty(Pos cell);

        /** {@link #support(BlockFacts, boolean)} of the block there; loaded. */
        boolean support(Pos block);

        /**
         * {@link #safe(BlockFacts)} of the block there: no fluid (waterlogged included), fire, soul fire, lava, cactus,
         * lava cauldron, hopper, nether portal, end portal or end gateway; false when not loaded.
         */
        boolean safe(Pos block);

        /**
         * Ruling R57: a drop drifting sideways passes through this cell: loaded, an empty collision shape and no fluid,
         * entities ignored (air, a torch, a rail, a flower, a button, redstone dust all pass). Unlike {@link #empty} it
         * does not ask whether a block could be placed there, so a cell the player's body overlaps still passes.
         */
        boolean dropPasses(Pos cell);

        /**
         * Rulings R51 and R58: the block there stops a drop drifting sideways: loaded and a full-cube collision shape
         * only. A fence, pane, iron bars, wall, chain, door, fence gate or scaffolding is neither this nor
         * {@link #dropPasses}, so a cell with one beside it is refused.
         */
        boolean stopsDrop(Pos cell);

        /**
         * Rulings R51 and R56: the block there is a floor a drop may come to rest on and stay: loaded, a top face that is
         * a full square ({@code isSideSolidFullSquare(UP)}: not powder snow, a slab, a layer of snow), the default
         * slipperiness 0.6 (no ice, packed ice, frosted ice, blue ice or slime, on which a drop slides 3 or 4 blocks),
         * not air or the void below the world, not a fluid, and {@link #safe(BlockFacts)}. The support the box is set on
         * is asked too.
         */
        boolean floor(Pos block);
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
                    if (!world.empty(cell) || !world.empty(lid)) continue;
                    if (!world.support(support) || !world.floor(support)) continue;
                    if (!Aim.facesEye(support, Face.UP, eye)) continue;
                    Point hit = Aim.hitPoint(support, Face.UP, eye, margin);
                    double d = eye.distance(hit);
                    if (d > reach) continue;
                    if (best != null && (d > bestD || (d == bestD && !before(cell, best.cell())))) continue;
                    if (!dropLandsSafely(cell, world)) continue;
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

    /**
     * Ruling R51: the broken box's drop spawns within 0.25 of the cell's centre and drifts up to about 1.5 blocks.
     * Nothing
     * from the support's level to the lid's level, over the 3 x 3 columns around the cell, is a hazard ({@link
     * World#safe}); and each of the 8 neighbour columns, at the cell's level, either stops the drift or lets it pass
     * with a floor under it, so the drop never falls off an edge or into the void.
     */
    private static boolean dropLandsSafely(Pos cell, World world) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!world.safe(new Pos(cell.x() + dx, cell.y() + dy, cell.z() + dz))) return false;
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Pos column = new Pos(cell.x() + dx, cell.y(), cell.z() + dz);
                if (world.stopsDrop(column)) continue;
                if (!world.dropPasses(column) || !world.floor(column.offset(Face.DOWN))) return false;
            }
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
