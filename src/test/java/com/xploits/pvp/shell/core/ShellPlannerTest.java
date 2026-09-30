package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The block plan (surround++ spec §5.3-5.6, §7.4). Every expected value is worked out by hand in the comment next to it. */
class ShellPlannerTest {
    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    private static Placement p(int x, int y, int z, Material m, ShellReason r) {
        return new Placement(c(x, y, z), m, r);
    }

    private static List<Placement> plan(ShellSnapshot s, FakeOracle oracle, int budget) {
        return ShellPlanner.plan(new ThreatMap(s, oracle), budget, false).placements();
    }

    @Test
    void theWorstSpotIsFilledFirst() {
        // Obsidian floor: (1,0,0) deals 10, (0,0,1) deals 6. One block this tick: the 10.
        List<Placement> placed = plan(Scenes.obsidianFloor().cryingObsidian(0).build(),
            new FakeOracle().at(1, 0, 0, 10).at(0, 0, 1, 6), 1);
        assertEquals(List.of(p(1, 0, 0, Material.OBSIDIAN, ShellReason.SPOT)), placed);
    }

    @Test
    void plainObsidianIsUsedWhereCryingObsidianWouldDoNoBetter() {
        // Filling (1,0,0) with either takes its 10 away; obsidian makes (1,1,0) a spot, but it deals nothing: a tie, and
        // the tie goes to obsidian, to keep the crying obsidian for where it matters.
        List<Placement> placed = plan(Scenes.obsidianFloor().build(), new FakeOracle().at(1, 0, 0, 10), 2);
        assertEquals(List.of(p(1, 0, 0, Material.OBSIDIAN, ShellReason.SPOT)), placed);
    }

    @Test
    void cryingObsidianIsUsedWhereObsidianWouldMakeANewSpot() {
        // (1,0,0) deals 10; (1,1,0) above it deals 8 and now needs its base (weighs 4). Obsidian at (1,0,0): 10 goes, and
        // (1,1,0) turns real with one ray in three open, 8/3: 10 + 4 - 2.67 = 11.33. Crying obsidian: 10 + 4 = 14.
        List<Placement> placed = plan(Scenes.obsidianFloor().build(), new FakeOracle().at(1, 0, 0, 10).at(1, 1, 0, 8), 2);
        assertEquals(List.of(p(1, 0, 0, Material.CRYING_OBSIDIAN, ShellReason.SPOT)), placed);
    }

    @Test
    void cryingObsidianGoesWhereTheirBaseWouldGo() {
        // Deepslate floor, (1,1,0) at your head deals 12 and needs its base: weighs 6. Filling it is impossible (nothing
        // around it to place against). Crying obsidian at (1,0,0): 6 per block. The wall and the block over it: 6 for two.
        List<Placement> placed = plan(Scenes.open().build(), new FakeOracle().at(1, 1, 0, 12), 2);
        assertEquals(List.of(p(1, 0, 0, Material.CRYING_OBSIDIAN, ShellReason.BASE)), placed);
    }

    @Test
    void withoutCryingObsidianItBuildsTheWallAndTheBlockOverIt() {
        // No crying obsidian. Obsidian at (1,0,0) alone makes (1,1,0) real with one ray in three open: 6 - 4 = 2 for one
        // block. The wall and then the block over it: 6 for two, 3 per block.
        List<Placement> placed = plan(Scenes.open().cryingObsidian(0).build(), new FakeOracle().at(1, 1, 0, 12), 2);
        assertEquals(List.of(p(1, 0, 0, Material.OBSIDIAN, ShellReason.SUPPORT), p(1, 1, 0, Material.OBSIDIAN, ShellReason.SPOT)),
            placed);
    }

    @Test
    void withOneBlockThisTickItStartsWithTheWall() {
        List<Placement> placed = plan(Scenes.open().cryingObsidian(0).build(), new FakeOracle().at(1, 1, 0, 12), 1);
        assertEquals(List.of(p(1, 0, 0, Material.OBSIDIAN, ShellReason.SUPPORT)), placed);
    }

    @Test
    void aTwoBlockOptionNeedsTwoBlocksInTheHotbar() {
        // One obsidian: the pair is not offered; the wall alone as a shield (2) is the best one block does.
        List<Placement> placed = plan(Scenes.open().obsidian(1).cryingObsidian(0).build(), new FakeOracle().at(1, 1, 0, 12), 2);
        assertEquals(List.of(p(1, 0, 0, Material.OBSIDIAN, ShellReason.SHIELD)), placed);
    }

    @Test
    void theBudgetCapsTheBlocks() {
        List<Placement> placed = plan(Scenes.obsidianFloor().cryingObsidian(0).build(),
            new FakeOracle().at(1, 0, 0, 10).at(-1, 0, 0, 8).at(0, 0, 1, 6), 2);
        assertEquals(List.of(c(1, 0, 0), c(-1, 0, 0)), placed.stream().map(Placement::cell).toList());
    }

    @Test
    void aSpotBelowTheDangerLineIsLeftAlone() {
        assertTrue(plan(Scenes.obsidianFloor().build(), new FakeOracle().at(1, 0, 0, 1.9), 2).isEmpty());
        assertEquals(1, plan(Scenes.obsidianFloor().build(), new FakeOracle().at(1, 0, 0, 2.0), 2).size());
    }

    @Test
    void withNobodyAroundNothingIsPlaced() {
        ShellSnapshot s = ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).build();
        assertTrue(plan(s, new FakeOracle().at(1, 0, 0, 10), 8).isEmpty());
    }

    @Test
    void withAnEmptyHotbarNothingIsPlaced() {
        assertTrue(plan(Scenes.obsidianFloor().obsidian(0).cryingObsidian(0).build(), new FakeOracle().at(1, 0, 0, 10), 2).isEmpty());
    }

    @Test
    void cryingObsidianIsNotUsedWithTheSettingOff() {
        ShellSettings off = new ShellSettings(2, false, true, true, true, false, 6);
        List<Placement> placed = plan(Scenes.open().settings(off).build(), new FakeOracle().at(1, 1, 0, 12), 2);
        assertTrue(placed.stream().noneMatch(pl -> pl.material() == Material.CRYING_OBSIDIAN), placed.toString());
    }

    @Test
    void aSpotOurAuraIsUsingIsLeftFree() {
        ShellPlanner.Result r = ShellPlanner.plan(new ThreatMap(Scenes.obsidianFloor().auraSpot(c(1, 0, 0)).build(),
            new FakeOracle().at(1, 0, 0, 10)), 2, false);
        assertTrue(r.placements().isEmpty());
        assertFalse(r.auraOverride());
    }

    @Test
    void theNextThreatIsTakenWhenTheWorstIsLeftToTheAura() {
        List<Placement> placed = plan(Scenes.obsidianFloor().cryingObsidian(0).auraSpot(c(1, 0, 0)).build(),
            new FakeOracle().at(1, 0, 0, 10).at(-1, 0, 0, 8), 1);
        assertEquals(List.of(p(-1, 0, 0, Material.OBSIDIAN, ShellReason.SPOT)), placed);
    }

    @Test
    void theAuraSpotIsCoveredWhenACrystalThereCouldTakeUsBelowTheFloor() {
        // 11 health: a 10 there would leave 1, below the floor of 2.
        ShellPlanner.Result r = ShellPlanner.plan(new ThreatMap(Scenes.obsidianFloor().cryingObsidian(0).health(11)
            .auraSpot(c(1, 0, 0)).build(), new FakeOracle().at(1, 0, 0, 10)), 2, false);
        assertEquals(List.of(p(1, 0, 0, Material.OBSIDIAN, ShellReason.SPOT)), r.placements());
        assertTrue(r.auraOverride());
    }

    @Test
    void holdingClosedClosesTheRingThenTheRoof() {
        // No threat at all: after a pop the closure still runs. Feet ring first (east, west, south, north), then the head ring.
        List<Placement> rings = ShellPlanner.plan(new ThreatMap(Scenes.open().build(), new FakeOracle()), 8, true).placements();
        assertEquals(List.of(c(1, 0, 0), c(-1, 0, 0), c(0, 0, 1), c(0, 0, -1), c(1, 1, 0), c(-1, 1, 0), c(0, 1, 1), c(0, 1, -1)),
            rings.stream().map(Placement::cell).toList());
        assertTrue(rings.stream().allMatch(pl -> pl.material() == Material.CRYING_OBSIDIAN && pl.reason() == ShellReason.CLOSURE));
        // With both rings standing: a block above the east one, then the roof against it.
        ShellSnapshot ringed = Scenes.open().fill(-1, 0, 0, 1, 1, 0, BlockKind.OBSIDIAN).fill(0, 0, -1, 0, 1, 1, BlockKind.OBSIDIAN)
            .block(0, 0, 0, BlockKind.AIR).block(0, 1, 0, BlockKind.AIR).build();
        List<Placement> roof = ShellPlanner.plan(new ThreatMap(ringed, new FakeOracle()), 8, true).placements();
        assertEquals(List.of(c(1, 2, 0), c(0, 2, 0)), roof.stream().map(Placement::cell).toList());
    }

    @Test
    void withoutHoldingClosedNoClosureIsPlaced() {
        assertTrue(ShellPlanner.plan(new ThreatMap(Scenes.open().build(), new FakeOracle()), 8, false).placements().isEmpty());
    }

    @Test
    void nothingIsPlannedInsideABodyThatSpansThreeCells() {
        // Half a block up (a slab): the body is (0,0,0), (0,1,0), (0,2,0). The closure rings every level of it.
        ShellSnapshot s = Scenes.open().feet(new Vec(0.5, 0.5, 0.5)).build();
        List<Placement> placed = ShellPlanner.plan(new ThreatMap(s, new FakeOracle()), 8, true).placements();
        assertEquals(8, placed.size());
        assertTrue(placed.stream().noneMatch(pl -> s.body().contains(pl.cell())), placed.toString());
        assertEquals(List.of(c(1, 0, 0), c(-1, 0, 0), c(0, 0, 1), c(0, 0, -1), c(1, 1, 0), c(-1, 1, 0), c(0, 1, 1), c(0, 1, -1)),
            placed.stream().map(Placement::cell).toList());
    }

    @Test
    void aBlockOutOfOurReachIsNeverPlanned() {
        // (4,0,4) is sqrt(16 + 1.25 + 16) = 5.8 from our eyes, past 4.5; the opponent standing next to it reaches it.
        ShellSnapshot s = ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).hostile(new Vec(4.5, 0, 3.5)).cryingObsidian(0).build();
        List<Placement> placed = plan(s, new FakeOracle().at(4, 0, 4, 10), 1);
        assertTrue(placed.stream().noneMatch(pl -> pl.cell().equals(c(4, 0, 4))), placed.toString());
    }

    @Test
    void aCellAnotherEntityStandsInIsNeverPlanned() {
        // One obsidian, so no pair, only a shield on the spot's rays: (1,0,0) is one of them, and an opponent stands in it.
        ShellSnapshot s = Scenes.open().obsidian(1).cryingObsidian(0).occupied(c(1, 0, 0)).build();
        List<Placement> placed = plan(s, new FakeOracle().at(2, 1, 0, 12), 2);
        assertEquals(1, placed.size());
        assertFalse(placed.get(0).cell().equals(c(1, 0, 0)), placed.toString());
    }

    @Test
    void aBlockNeverGoesOnTheAurasSpotToShieldAnother() {
        // crystal-aura++ is placing at (1,0,0). (1,1,0) above it deals 12 and needs its base: the only blocks that would
        // deal with it are a shield or a support at (1,0,0), the aura's spot, and there is no crying obsidian for its base.
        // The spot is left alone rather than blocking our own aura.
        List<Placement> placed = plan(Scenes.open().cryingObsidian(0).auraSpot(c(1, 0, 0)).build(),
            new FakeOracle().at(1, 1, 0, 12), 2);
        assertTrue(placed.isEmpty(), placed.toString());
    }
}
