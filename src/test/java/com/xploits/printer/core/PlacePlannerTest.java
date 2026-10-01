package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xploits.printer.core.Fixtures.air;
import static com.xploits.printer.core.Fixtures.block;
import static com.xploits.printer.core.Fixtures.cell;
import static com.xploits.printer.core.Fixtures.outside;
import static com.xploits.printer.core.Fixtures.stone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Printer spec §4 PlacePlanner, §5.2: one action per tick, lowest then nearest in reach, supports, never-break. */
class PlacePlannerTest {
    private static final PlacePlanner PLANNER = new PlacePlanner(PrinterLimits.DEFAULTS);
    private static final PlacePlanner.RayOracle ALL = (b, s, r) -> true;

    /** A floor of stone outside the placement at y = -1, air inside it above, x -2..3, y 0..2, z -1..5. */
    private static final class Snap {
        Point eye = new Point(0.5, 1.62, 0.5);
        double reach = 4.5;
        boolean fix = true;
        final Map<Pos, Cell> cells = new HashMap<>();
        final Set<Pos> blocked = new HashSet<>();
        final Set<Pos> standing = new HashSet<>();
        final Set<Pos> pending = new HashSet<>();
        final Set<Pos> digging = new HashSet<>();
        final Set<Pos> skipped = new HashSet<>();
        final Set<Pos> broken = new HashSet<>();
        final Map<String, Integer> carried = new HashMap<>(Map.of("minecraft:stone", 64, "minecraft:red_carpet", 8));
        final Map<Pos, BreakPlan.Choice> tools = new HashMap<>();

        Snap() {
            for (int x = -2; x <= 3; x++) {
                for (int z = -1; z <= 5; z++) {
                    cells.put(new Pos(x, -1, z), outside(x, -1, z, stone()));
                    for (int y = 0; y <= 2; y++) cells.put(new Pos(x, y, z), cell(x, y, z, Target.AIR, air()));
                }
            }
        }

        Snap want(int x, int y, int z, BlockFacts target, BlockFacts world) {
            cells.put(new Pos(x, y, z), cell(x, y, z, Target.of(target), world));
            return this;
        }

        Snap world(int x, int y, int z, BlockFacts world) {
            cells.put(new Pos(x, y, z), cell(x, y, z, Target.AIR, world));
            return this;
        }

        BuildSnapshot build() {
            return new BuildSnapshot(eye, 0f, reach, fix, cells, blocked, standing, pending, digging, skipped, broken, carried, tools);
        }
    }

    private static Snap twoTargets() {
        return new Snap().want(0, 0, 3, stone(), air()).want(1, 0, 2, stone(), air()).want(0, 1, 3, stone(), air());
    }

    @Test
    void lowestFirstThenNearestAgainstTheFloor() {
        PlacePlanner.Place p = assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(twoTargets().build(), ALL));
        assertEquals(new Pos(1, 0, 2), p.target());
        assertEquals(new Pos(1, -1, 2), p.support());
        assertEquals(Face.UP, p.side());
        assertEquals(1.1, p.hit().x(), 1e-9);
        assertEquals(0.0, p.hit().y(), 1e-9);
        assertEquals(2.1, p.hit().z(), 1e-9);
        assertEquals(-20.556046f, p.rotation().yaw(), 1e-4f);
        assertEquals(43.471912f, p.rotation().pitch(), 1e-4f);
        assertEquals("minecraft:stone", p.material());
    }

    @Test
    void aLowerTargetComesBeforeANearerHigherOne() {
        // (1,1,1) is nearer (centre distance² 2.0144) but one layer up; (0,0,4) is at 17.2544 on the bottom layer.
        Snap s = new Snap().want(0, 0, 4, stone(), air()).want(1, 1, 1, stone(), air()).world(1, 0, 1, stone());
        assertEquals(new Pos(0, 0, 4), assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(s.build(), ALL)).target());
    }

    @Test
    void aFaceTheRayCannotSeeIsSkippedForTheNextCandidate() {
        List<String> asked = new ArrayList<>();
        PlacePlanner.RayOracle notThere = (b, s, r) -> {
            asked.add(b + " " + s);
            return !(b.equals(new Pos(1, -1, 2)) && s == Face.UP);
        };
        PlacePlanner.Place p = assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(twoTargets().build(), notThere));
        assertEquals(new Pos(0, 0, 3), p.target());
        assertEquals(new Pos(0, -1, 3), p.support());
        assertEquals(0.0f, p.rotation().yaw(), 1e-4f);
        assertEquals(31.92608f, p.rotation().pitch(), 1e-4f);
        assertEquals(List.of("Pos[x=1, y=-1, z=2] UP", "Pos[x=0, y=-1, z=3] UP"), asked);
    }

    @Test
    void aBlockWithNoSupportIsDeferred() {
        Snap s = new Snap().want(0, 1, 3, stone(), air());
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NO_FACE), PLANNER.decide(s.build(), ALL));
    }

    @Test
    void pendingSkippedAndEntityBlockedPositionsAreLeftOut() {
        for (int k = 0; k < 3; k++) {
            Snap s = twoTargets();
            Set<Pos> set = k == 0 ? s.pending : k == 1 ? s.skipped : s.blocked;
            set.add(new Pos(1, 0, 2));
            PlacePlanner.Place p = assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(s.build(), ALL));
            assertEquals(new Pos(0, 0, 3), p.target());
        }
    }

    @Test
    void nothingCarriedNothingToDo() {
        Snap s = twoTargets();
        s.carried.clear();
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NOTHING_TO_DO), PLANNER.decide(s.build(), ALL));
    }

    @Test
    void reachIsMeasuredToTheHitPoint() {
        Snap near = twoTargets();
        near.reach = 2.0;
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NO_FACE), PLANNER.decide(near.build(), ALL));
        Snap just = twoTargets();
        just.reach = 2.4;
        assertEquals(new Pos(1, 0, 2), assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(just.build(), ALL)).target());
    }

    @Test
    void aCarpetWaitsForABlockBelowIt() {
        BlockFacts carpet = block("red_carpet").notFull().build();
        Snap s = new Snap().want(1, 1, 2, carpet, air()).world(2, 1, 2, stone());
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NOTHING_TO_DO), PLANNER.decide(s.build(), ALL));
        s.world(1, 0, 2, stone());
        PlacePlanner.Place p = assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(s.build(), ALL));
        assertEquals(new Pos(1, 0, 2), p.support());
        assertEquals(Face.UP, p.side());
        assertEquals("minecraft:red_carpet", p.material());
    }

    private static Snap wrongAt102() {
        Snap s = new Snap().want(1, 0, 2, stone(), block("cobblestone").build());
        s.tools.put(new Pos(1, 0, 2), new BreakPlan.Choice(1, 0.17777778f, 6));
        return s;
    }

    @Test
    void aWrongBlockIsDugFromItsNearestVisibleFace() {
        PlacePlanner.Dig d = assertInstanceOf(PlacePlanner.Dig.class, PLANNER.decide(wrongAt102().build(), ALL));
        assertEquals(new Pos(1, 0, 2), d.target());
        assertEquals(Face.NORTH, d.side());
        assertEquals(1.1, d.hit().x(), 1e-9);
        assertEquals(0.9, d.hit().y(), 1e-9);
        assertEquals(2.0, d.hit().z(), 1e-9);
        assertEquals(-21.801409f, d.rotation().yaw(), 1e-4f);
        assertEquals(24.021042f, d.rotation().pitch(), 1e-4f);
        assertEquals(new BreakPlan.Choice(1, 0.17777778f, 6), d.tool());
        assertEquals("minecraft:stone", d.material());
    }

    @Test
    void theNextVisibleFaceIsTriedWhenTheNearestIsHidden() {
        PlacePlanner.RayOracle notNorth = (b, s, r) -> s != Face.NORTH;
        PlacePlanner.Dig d = assertInstanceOf(PlacePlanner.Dig.class, PLANNER.decide(wrongAt102().build(), notNorth));
        assertEquals(Face.UP, d.side());
        assertEquals(19.942167f, d.rotation().pitch(), 1e-4f);
    }

    @Test
    void wrongBlocksWaitWhileFixWrongBlocksIsOff() {
        Snap s = wrongAt102();
        s.fix = false;
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NOTHING_TO_DO), PLANNER.decide(s.build(), ALL));
    }

    @Test
    void aProtectedWrongBlockIsPassedOver() {
        Snap s = new Snap().want(1, 0, 2, stone(), block("chest").props().blockEntity().build()).want(0, 0, 3, stone(), air());
        s.tools.put(new Pos(1, 0, 2), new BreakPlan.Choice(1, 0.17777778f, 6));
        assertEquals(new Pos(0, 0, 3), assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(s.build(), ALL)).target());
    }

    @Test
    void withoutAToolTheWrongBlockIsTooSlowAndPassedOver() {
        Snap s = wrongAt102();
        s.tools.clear();
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NOTHING_TO_DO), PLANNER.decide(s.build(), ALL));
    }

    @Test
    void aWrongBlockAlreadyBrokenOnceHaltsThePrinter() {
        Snap s = wrongAt102();
        s.broken.add(new Pos(1, 0, 2));
        assertEquals(new PlacePlanner.Halt(PhaseRules.NeverBreak.ALREADY_BROKEN, new Pos(1, 0, 2)), PLANNER.decide(s.build(), ALL));
    }

    @Test
    void aWrongBlockStillBeingDugIsLeftAloneEvenIfItIsInBroken() {
        Snap s = wrongAt102();
        s.digging.add(new Pos(1, 0, 2));
        s.broken.add(new Pos(1, 0, 2));
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.NOTHING_TO_DO), PLANNER.decide(s.build(), ALL));
    }

    @Test
    void theRayBudgetIsHonoured() {
        PrinterLimits one = new PrinterLimits(100, 10, 0.1, 3, 20, 40, 3, 3, 4, -101, 4, 4.5, 6, 1.5, 16384, 4_194_304L,
            1, 200, 0.5);
        PlacePlanner.RayOracle never = (b, s, r) -> false;
        assertEquals(new PlacePlanner.Idle(PlacePlanner.IdleReason.RAY_BUDGET),
            new PlacePlanner(one).decide(twoTargets().build(), never));
    }

    @Test
    void thePlayersOwnCellsAreLeftAlone() {
        Snap s = new Snap().want(0, 0, 0, stone(), air()).want(0, 1, 0, stone(), air()).want(1, 0, 0, stone(), air());
        s.cells.put(new Pos(0, -1, 0), cell(0, -1, 0, Target.of(stone()), block("cobblestone").build()));
        s.cells.put(new Pos(0, -2, 0), outside(0, -2, 0, stone()));
        s.tools.put(new Pos(0, -1, 0), new BreakPlan.Choice(1, 0.17777778f, 6));
        s.blocked.add(new Pos(0, 0, 0));
        s.blocked.add(new Pos(0, 1, 0));
        s.standing.add(new Pos(0, -1, 0));
        PlacePlanner.Place p = assertInstanceOf(PlacePlanner.Place.class, PLANNER.decide(s.build(), ALL));
        assertEquals(new Pos(1, 0, 0), p.target());
        assertEquals(new Pos(1, -1, 0), p.support());
    }
}
