package com.xploits.restock.core;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.BlockFacts;
import com.xploits.printer.core.GridBox;
import com.xploits.printer.core.PlacePlanner;
import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restock spec §3 "Shulkers at the build": a free cell beside the player, outside the build, with room for the lid, on a
 * block a click never uses, away from what burns or carries off a drop, clicked on that block's top. The floor is at
 * y 63; the player stands centred on (0, 64, 0), eye 1.62 up. Hand-worked: a side neighbour's support top is hit 0.6
 * from the eye's column (the face's edge kept 0.1 away), so the hit is √(0.6² + 1.62²) = 1.72754 away; a diagonal's
 * √(0.6² + 0.6² + 1.62²) = 1.82877. On the +x and +z sides the offset is 1.1 − 0.5 = 0.6000000000000001 in doubles, a
 * hair farther: of the four side neighbours only (−1, 64, 0) and (0, 64, −1) tie exactly, and the lower x wins.
 */
class ShulkerSpotTest {
    private static final Point FEET = new Point(0.5, 64, 0.5);
    private static final Point EYE = new Point(0.5, 65.62, 0.5);
    /** The build: a row two blocks in front, away from every cell the tests expect. */
    private static final List<GridBox> BUILD = List.of(GridBox.of(new Pos(-1, 64, 2), new Pos(1, 65, 2)));
    private static final PlacePlanner.RayOracle SEES_ALL = (block, side, r) -> true;

    /** Air from y 64 up except {@code blocked}; every block at y 63 is a support; {@code hazards} are unsafe. */
    private static ShulkerSpot.World floor(Set<Pos> blocked, Set<Pos> hazards) {
        return new ShulkerSpot.World() {
            @Override
            public boolean empty(Pos cell) {
                return cell.y() >= 64 && !blocked.contains(cell);
            }

            @Override
            public boolean support(Pos block) {
                return block.y() == 63;
            }

            @Override
            public boolean safe(Pos block) {
                return !hazards.contains(block);
            }

            @Override
            public boolean dropPasses(Pos cell) {
                return cell.y() >= 64 && !blocked.contains(cell);
            }

            @Override
            public boolean stopsDrop(Pos cell) {
                return blocked.contains(cell);
            }

            @Override
            public boolean floor(Pos block) {
                return block.y() == 63 && !hazards.contains(block);
            }
        };
    }

    private static ShulkerSpot.World floor() {
        return floor(Set.of(), Set.of());
    }

    private static Optional<ShulkerSpot.Choice> choose(List<GridBox> boxes, Set<Pos> tried, double reach,
                                                       ShulkerSpot.World world, PlacePlanner.RayOracle oracle) {
        return ShulkerSpot.choose(boxes, FEET, EYE, 0f, tried, reach, 0.1, world, oracle);
    }

    /** id, then: air, block entity, replaceable, fluid; a full cube with no properties otherwise. */
    private static BlockFacts block(String id, boolean blockEntity, boolean replaceable, boolean fluid) {
        return new BlockFacts(id, id, false, true, true, true, blockEntity, false, false, replaceable, fluid, false);
    }

    @Test
    void theNearestFreeCellBesideThePlayerNeverUnderIt() {
        // (0, 64, 0) would be nearest (1.62) but the player stands in it.
        ShulkerSpot.Choice c = choose(BUILD, Set.of(), 4.5, floor(), SEES_ALL).orElseThrow();
        assertEquals(new Pos(-1, 64, 0), c.cell());
        assertEquals(new Pos(-1, 63, 0), c.support());
        assertEquals(new Point(-0.1, 64, 0.5), c.hit());
        // From the eye to the hit: dx -0.6, dy -1.62, dz 0 → yaw 90, pitch atan(1.62 / 0.6) = 69.67686.
        assertEquals(90.0f, c.rotation().yaw(), 1e-4f);
        assertEquals(69.67686f, c.rotation().pitch(), 1e-4f);
    }

    @Test
    void theCellsAlreadyTriedAreSkipped() {
        Set<Pos> tried = Set.of(new Pos(-1, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, -1), new Pos(0, 64, 1));
        assertEquals(new Pos(-1, 64, -1), choose(BUILD, tried, 4.5, floor(), SEES_ALL).orElseThrow().cell(),
            "the diagonals come next (1.82877), the lowest x then the lowest z");
    }

    @Test
    void neitherTheCellNorItsLidIsInsideTheBuild() {
        List<GridBox> column = List.of(GridBox.of(new Pos(-1, 64, -1), new Pos(-1, 65, 1)));
        assertEquals(new Pos(0, 64, -1), choose(column, Set.of(), 4.5, floor(), SEES_ALL).orElseThrow().cell());
        List<GridBox> lidOnly = List.of(GridBox.of(new Pos(-1, 65, 0), new Pos(-1, 65, 0)));
        assertEquals(new Pos(0, 64, -1), choose(lidOnly, Set.of(), 4.5, floor(), SEES_ALL).orElseThrow().cell());
    }

    @Test
    void theLidNeedsRoom() {
        assertEquals(new Pos(0, 64, -1),
            choose(BUILD, Set.of(), 4.5, floor(Set.of(new Pos(-1, 65, 0)), Set.of()), SEES_ALL).orElseThrow().cell());
    }

    @Test
    void theRayMustSeeTheSupportsTop() {
        PlacePlanner.RayOracle blind = (block, side, r) -> !block.equals(new Pos(-1, 63, 0));
        assertEquals(new Pos(0, 64, -1), choose(BUILD, Set.of(), 4.5, floor(), blind).orElseThrow().cell());
    }

    @Test
    void reachIsMeasuredToTheHit() {
        assertEquals(Optional.empty(), choose(BUILD, Set.of(), 1.72, floor(), SEES_ALL));
        assertEquals(new Pos(-1, 64, 0), choose(BUILD, Set.of(), 1.73, floor(), SEES_ALL).orElseThrow().cell());
        // A click exactly at reach is within it: the hit's own distance as the reach.
        double exact = EYE.distance(new Point(-0.1, 64, 0.5));
        assertEquals(new Pos(-1, 64, 0), choose(BUILD, Set.of(), exact, floor(), SEES_ALL).orElseThrow().cell());
    }

    @Test
    void aTieAcrossHeightsGoesToTheLowerXNotTheLowerY() {
        // Margin 0 and an eye at 65.5 make two clicks exactly 2.5 away in binary: the side neighbour's support top (0.5
        // across, 1.5 down) and, two blocks west and one up, a support top (1.5 across, 0.5 down). The lower x wins although
        // the other cell is lower.
        Point eye = new Point(0.5, 65.5, 0.5);
        ShulkerSpot.World two = new ShulkerSpot.World() {
            @Override
            public boolean empty(Pos cell) {
                return true;
            }

            @Override
            public boolean support(Pos block) {
                return block.equals(new Pos(-1, 63, 0)) || block.equals(new Pos(-2, 64, 0));
            }

            @Override
            public boolean safe(Pos block) {
                return true;
            }

            @Override
            public boolean dropPasses(Pos cell) {
                return true;
            }

            @Override
            public boolean stopsDrop(Pos cell) {
                return false;
            }

            @Override
            public boolean floor(Pos block) {
                return true;
            }
        };
        assertEquals(new Pos(-2, 65, 0),
            ShulkerSpot.choose(BUILD, FEET, eye, 0f, Set.of(), 4.5, 0.0, two, SEES_ALL).orElseThrow().cell());
    }

    /** The world of {@link #floor}, with the ground and the walls changed for one test of the landing rules. */
    private static ShulkerSpot.World landing(Set<Pos> walls, Set<Pos> noFloor, Set<Pos> hazards) {
        return landing(walls, noFloor, hazards, Set.of(), Set.of());
    }

    private static ShulkerSpot.World landing(Set<Pos> walls, Set<Pos> noFloor, Set<Pos> hazards, Set<Pos> partial) {
        return landing(walls, noFloor, hazards, partial, Set.of());
    }

    /**
     * {@code partial}: a block with a collision shape that does not fill the cell, such as a fence: neither empty nor a
     * wall. {@code small}: a block with no collision that the box could not be placed in (a torch, or any cell the
     * player's body overlaps): not {@code empty}, yet a drop passes through it.
     */
    private static ShulkerSpot.World landing(Set<Pos> walls, Set<Pos> noFloor, Set<Pos> hazards, Set<Pos> partial,
                                             Set<Pos> small) {
        return new ShulkerSpot.World() {
            @Override
            public boolean empty(Pos cell) {
                return cell.y() >= 64 && !walls.contains(cell) && !partial.contains(cell) && !small.contains(cell);
            }

            @Override
            public boolean support(Pos block) {
                return block.y() == 63;
            }

            @Override
            public boolean safe(Pos block) {
                return !hazards.contains(block);
            }

            @Override
            public boolean dropPasses(Pos cell) {
                return cell.y() >= 64 && !walls.contains(cell) && !partial.contains(cell);
            }

            @Override
            public boolean stopsDrop(Pos cell) {
                return walls.contains(cell);
            }

            @Override
            public boolean floor(Pos block) {
                return block.y() == 63 && !noFloor.contains(block);
            }
        };
    }

    private static final Pos NEAREST = new Pos(-1, 64, 0);
    private static final Pos NEXT = new Pos(0, 64, -1);

    private static Pos cellFor(ShulkerSpot.World world) {
        return choose(BUILD, Set.of(), 4.5, world, SEES_ALL).orElseThrow().cell();
    }

    @Test
    void aNeighbourColumnWithNoFloorIsAnEdgeAndRefusesTheCell() {
        // Ruling R51: the drop drifts sideways; over a column with nothing under it, it falls.
        assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(new Pos(-2, 63, 0)), Set.of())), "orthogonal edge");
        assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(new Pos(-2, 63, 1)), Set.of())), "diagonal edge");
    }

    @Test
    void aFenceLikeNeighbourIsNeitherAWallNorAPassage() {
        // Ruling R58: a partial collision shape (fence, pane, wall, door, scaffolding) can hold the drop or let it by, so refuse.
        assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(), Set.of(), Set.of(new Pos(-2, 64, 0)))));
    }

    @Test
    void aNeighbourTheBoxCouldNotBePlacedInButADropPassesIsAPassage() {
        // Ruling R57: a torch beside the cell, or a cell the player's body overlaps, fails `empty` yet a drop passes it.
        assertEquals(NEAREST, cellFor(landing(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(new Pos(-2, 64, 0)))));
        assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(new Pos(-2, 63, 0)), Set.of(), Set.of(),
            Set.of(new Pos(-2, 64, 0)))), "but it still needs its floor");
    }

    /** What the adapter's floor answers: {@code ids} names the block at each position; the default is stone. */
    private static ShulkerSpot.World iceWorld(java.util.Map<Pos, String> ids) {
        Set<String> slippery = Set.of("minecraft:ice", "minecraft:packed_ice", "minecraft:frosted_ice",
            "minecraft:blue_ice", "minecraft:slime_block");
        return new ShulkerSpot.World() {
            @Override
            public boolean empty(Pos cell) {
                return cell.y() >= 64;
            }

            @Override
            public boolean dropPasses(Pos cell) {
                return cell.y() >= 64;
            }

            @Override
            public boolean stopsDrop(Pos cell) {
                return false;
            }

            @Override
            public boolean support(Pos block) {
                return block.y() == 63;
            }

            @Override
            public boolean safe(Pos block) {
                return true;
            }

            @Override
            public boolean floor(Pos block) {
                return block.y() == 63 && !slippery.contains(ids.getOrDefault(block, "minecraft:stone"));
            }
        };
    }

    @Test
    void aSlipperyFloorUnderANeighbourOrTheSupportRefusesTheCell() {
        // Ruling R56: a drop slides 3 to 4 blocks on ice, packed ice, blue ice or slime; the support is the floor too.
        for (String id : new String[]{"minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
            "minecraft:slime_block", "minecraft:frosted_ice"}) {
            assertEquals(NEXT, cellFor(iceWorld(java.util.Map.of(new Pos(-2, 63, 0), id))), "neighbour on " + id);
            // The ice is also the floor of the neighbours it is under, so the next cell is the one east.
            assertEquals(new Pos(1, 64, 0), cellFor(iceWorld(java.util.Map.of(new Pos(-1, 63, 0), id))),
                "support on " + id);
        }
        assertEquals(NEAREST, cellFor(iceWorld(java.util.Map.of())), "the same world on stone");
    }

    @Test
    void aNeighbourThatStopsTheDriftNeedsNoFloor() {
        // A wall at the cell's level stops the drop; what lies under it does not matter.
        assertEquals(NEAREST, cellFor(landing(Set.of(new Pos(-2, 64, 0)), Set.of(new Pos(-2, 63, 0)), Set.of())));
    }

    @Test
    void lavaLevelWithTheSupportOnTheShoreRefusesTheCell() {
        assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(), Set.of(new Pos(-2, 63, 0)))), "beside the support");
        assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(), Set.of(new Pos(-2, 63, -1)))), "diagonal to the support");
    }

    @Test
    void aHazardOnADiagonalRefusesTheCell() {
        for (int y = 64; y <= 65; y++) {
            assertEquals(NEXT, cellFor(landing(Set.of(), Set.of(), Set.of(new Pos(-2, y, 1)))), "diagonal at y " + y);
        }
    }

    @Test
    void aHazardInTheLidCellOrOnAnySideRefusesTheCell() {
        for (Pos hazard : new Pos[]{new Pos(-1, 65, 0), new Pos(-1, 63, 0), new Pos(-2, 64, 0), new Pos(0, 64, 0),
            new Pos(-1, 64, 1), new Pos(-1, 64, -1)}) {
            assertNotEquals(NEAREST, cellFor(landing(Set.of(), Set.of(), Set.of(hazard))),
                "hazard at offset " + (hazard.x() - NEAREST.x()) + ", " + (hazard.y() - NEAREST.y()) + ", "
                    + (hazard.z() - NEAREST.z()) + " from the cell");
        }
    }

    @Test
    void aHazardAboveTheLidIsOutOfRange() {
        assertEquals(NEAREST, cellFor(landing(Set.of(), Set.of(), Set.of(new Pos(-2, 66, 0)))));
    }

    @Test
    void noSupportNoSpot() {
        ShulkerSpot.World none = new ShulkerSpot.World() {
            @Override
            public boolean empty(Pos cell) {
                return true;
            }

            @Override
            public boolean support(Pos block) {
                return false;
            }

            @Override
            public boolean safe(Pos block) {
                return true;
            }

            @Override
            public boolean dropPasses(Pos cell) {
                return true;
            }

            @Override
            public boolean stopsDrop(Pos cell) {
                return false;
            }

            @Override
            public boolean floor(Pos block) {
                return true;
            }
        };
        assertTrue(choose(BUILD, Set.of(), 4.5, none, SEES_ALL).isEmpty());
    }

    @Test
    void aCellNextToAHazardOrHoldingOneIsNeverUsed() {
        assertEquals(new Pos(0, 64, -1), choose(BUILD, Set.of(), 4.5,
            floor(Set.of(), Set.of(new Pos(-2, 64, 0))), SEES_ALL).orElseThrow().cell(), "lava west of the nearest cell");
        assertEquals(new Pos(1, 64, 0), choose(BUILD, Set.of(), 4.5,
            floor(Set.of(), Set.of(new Pos(-1, 64, 0))), SEES_ALL).orElseThrow().cell(), 
            "fire in it, and in the 3x3 of its neighbours");
        assertEquals(new Pos(1, 64, 0), choose(BUILD, Set.of(), 4.5,
            floor(Set.of(), Set.of(new Pos(-1, 63, 0))), SEES_ALL).orElseThrow().cell(), "an unsafe block under it");
    }

    @Test
    void theSupportFollowsRuling27() {
        assertTrue(ShulkerSpot.support(block("minecraft:stone", false, false, false), true));
        assertFalse(ShulkerSpot.support(block("minecraft:stone", false, false, false), false),
            "a top that is not a full square (a bottom slab)");
        assertFalse(ShulkerSpot.support(block("minecraft:respawn_anchor", false, false, false), true),
            "a click sets it off outside the Nether");
        assertFalse(ShulkerSpot.support(block("minecraft:crafting_table", false, false, false), true), "a click opens it");
        assertFalse(ShulkerSpot.support(block("minecraft:reinforced_deepslate", true, false, false), true),
            "any block entity, whatever its name");
        assertFalse(ShulkerSpot.support(block("minecraft:short_grass", false, true, false), true));
        assertFalse(ShulkerSpot.support(BlockFacts.AIR, true));
        assertFalse(ShulkerSpot.support(block("minecraft:water", false, true, true), true));
    }

    @Test
    void aDropIsSafeOnlyAwayFromFluidFireCactusAndWhatPullsItIn() {
        assertTrue(ShulkerSpot.safe(block("minecraft:stone", false, false, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:water", false, true, true)));
        assertFalse(ShulkerSpot.safe(block("minecraft:oak_stairs", false, false, true)), "waterlogged");
        assertFalse(ShulkerSpot.safe(block("minecraft:lava", false, true, true)));
        assertFalse(ShulkerSpot.safe(block("minecraft:fire", false, true, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:soul_fire", false, true, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:cactus", false, false, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:lava_cauldron", false, false, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:hopper", true, false, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:nether_portal", false, false, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:end_portal", true, false, false)));
        assertFalse(ShulkerSpot.safe(block("minecraft:end_gateway", true, false, false)));
        assertTrue(ShulkerSpot.safe(block("minecraft:magma_block", false, false, false)), "hurts only living entities");
        assertTrue(ShulkerSpot.safe(block("minecraft:campfire", true, false, false)), "hurts only living entities");
    }

    @Test
    void aChoiceNeverPrintsAPosition() {
        String printed = new ShulkerSpot.Choice(new Pos(12345, 64, -6789), new Pos(12345, 63, -6789),
            new Point(12345.5, 64, -6788.5), new Aim.Rotation(90f, 60f)).toString();
        assertFalse(printed.contains("12345") || printed.contains("6789") || printed.contains("6788"), printed);
        assertTrue(printed.contains(HiddenPositions.HIDDEN), printed);
    }
}
