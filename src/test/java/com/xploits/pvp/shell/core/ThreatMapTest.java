package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a crystal could hurt us, and what our planned blocks change about it (surround++ spec §5.1-5.3). */
class ThreatMapTest {
    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    @Test
    void aSpotOnObsidianWithAirAboveIsAThreat() {
        ThreatMap map = new ThreatMap(Scenes.obsidianFloor().build(), new FakeOracle().at(1, 0, 0, 10));
        Threat t = map.threatAt(c(1, 0, 0)).orElseThrow();
        assertEquals(Threat.Kind.REAL, t.kind());
        assertEquals(10.0, t.weighted(), 1e-9);
    }

    @Test
    void bedrockIsABaseAndCryingObsidianIsNot() {
        FakeOracle oracle = new FakeOracle().at(1, 0, 0, 10);
        assertTrue(new ThreatMap(ShellSnapshot.builder().floor(BlockKind.BEDROCK).hostile(new Vec(4.5, 0, 0.5)).build(), oracle)
            .threatAt(c(1, 0, 0)).isPresent());
        assertTrue(new ThreatMap(ShellSnapshot.builder().floor(BlockKind.CRYING_OBSIDIAN).hostile(new Vec(4.5, 0, 0.5)).build(),
            oracle).threatAt(c(1, 0, 0)).isEmpty());
    }

    @Test
    void aSpotWhoseBaseTheOpponentMustPlaceFirstWeighsHalf() {
        // Deepslate floor: the cell at feet level is air over a solid block, so a base can be placed there.
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle().at(1, 1, 0, 12));
        Threat t = map.threatAt(c(1, 1, 0)).orElseThrow();
        assertEquals(Threat.Kind.NEEDS_BASE, t.kind());
        assertEquals(6.0, t.weighted(), 1e-9);
    }

    @Test
    void aSpotOverABaseCellWithNothingToPlaceItAgainstIsNoThreat() {
        // (1,1,0) is air and so is every cell around it: nobody can place a base there, so (1,2,0) is out.
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle().at(1, 2, 0, 12));
        assertTrue(map.threatAt(c(1, 2, 0)).isEmpty());
    }

    @Test
    void anythingInTheOneByTwoBoxAboveTheBaseTakesTheSpot() {
        FakeOracle oracle = new FakeOracle().at(1, 0, 0, 10);
        assertTrue(new ThreatMap(Scenes.obsidianFloor().occupied(c(1, 0, 0)).build(), oracle).threatAt(c(1, 0, 0)).isEmpty());
        assertTrue(new ThreatMap(Scenes.obsidianFloor().occupied(c(1, 1, 0)).build(), oracle).threatAt(c(1, 0, 0)).isEmpty());
    }

    @Test
    void aCrystalOnlyGoesIntoAir() {
        FakeOracle oracle = new FakeOracle().at(1, 0, 0, 10);
        assertTrue(new ThreatMap(Scenes.obsidianFloor().block(1, 0, 0, BlockKind.COBWEB).build(), oracle).threatAt(c(1, 0, 0)).isEmpty());
        assertTrue(new ThreatMap(Scenes.obsidianFloor().block(1, 0, 0, BlockKind.REPLACEABLE).build(), oracle).threatAt(c(1, 0, 0)).isEmpty());
    }

    @Test
    void aSpotNoOpponentReachesIsNoThreat() {
        FakeOracle oracle = new FakeOracle().at(1, 0, 0, 10);
        // Eyes 1.62 up: from x 10.5 the spot's bottom centre is sqrt(81 + 2.62) = 9.14 away, past 6 + 1.
        ShellSnapshot far = ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).hostile(new Vec(10.5, 0, 0.5)).build();
        assertTrue(new ThreatMap(far, oracle).threatAt(c(1, 0, 0)).isEmpty());
        // From x 7.5 it is sqrt(36 + 2.62) = 6.21: within 6 + 1.
        ShellSnapshot near = ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).hostile(new Vec(7.5, 0, 0.5)).build();
        assertTrue(new ThreatMap(near, oracle).threatAt(c(1, 0, 0)).isPresent());
    }

    @Test
    void withNobodyAroundThereIsNoThreat() {
        ThreatMap map = new ThreatMap(ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).build(), new FakeOracle().at(1, 0, 0, 10));
        assertTrue(map.threats().isEmpty());
    }

    @Test
    void threatsComeWorstFirst() {
        ThreatMap map = new ThreatMap(Scenes.obsidianFloor().build(),
            new FakeOracle().at(1, 0, 0, 10).at(0, 0, 1, 6).at(-1, 0, 0, 8));
        List<Cell> spots = map.threats().stream().map(Threat::spot).toList();
        assertEquals(List.of(c(1, 0, 0), c(-1, 0, 0), c(0, 0, 1)), spots);
    }

    @Test
    void aDamageThatIsNotANumberReadsAsTheWorst() {
        ThreatMap map = new ThreatMap(Scenes.obsidianFloor().build(),
            new FakeOracle().bound(1, 0, 0, 20).exact(1, 0, 0, Double.NaN));
        assertEquals(ThreatMap.UNKNOWN_DAMAGE, map.threatAt(c(1, 0, 0)).orElseThrow().damage(), 1e-9);
    }

    @Test
    void theRaycastIsOnlyAskedWhereTheBoundCouldMatter() {
        // Needs its base: a bound of 3 weighs 1.5, under the danger line, so the bound is kept and nothing is raycast.
        FakeOracle open = new FakeOracle().bound(1, 1, 0, 3);
        new ThreatMap(Scenes.open().build(), open).threatAt(c(1, 1, 0));
        assertFalse(open.askedExact.contains(c(1, 1, 0)));
        // A base already there: 3 weighs 3, over the line, so the raycast is asked for.
        FakeOracle real = new FakeOracle().bound(1, 0, 0, 3);
        new ThreatMap(Scenes.obsidianFloor().build(), real).threatAt(c(1, 0, 0));
        assertTrue(real.askedExact.contains(c(1, 0, 0)));
    }

    @Test
    void anObsidianBlockWePlanBecomesABaseAndClosesTheRaysItCrosses() {
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle().at(1, 1, 0, 12));
        map.place(c(1, 0, 0), Material.OBSIDIAN);
        Threat t = map.threatAt(c(1, 1, 0)).orElseThrow();
        assertEquals(Threat.Kind.REAL, t.kind());
        // By hand (SegmentsTest): the rays to our feet (y 0.2) and middle (y 0.9) go down through (1,0,0) first; the one
        // to our eyes (y 1.6) goes straight into our head cell. One of three is left open: 12 * 1/3.
        assertEquals(4.0, t.weighted(), 1e-9);
    }

    @Test
    void cryingObsidianWePlanUnderASpotTakesItAway() {
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle().at(1, 1, 0, 12));
        map.place(c(1, 0, 0), Material.CRYING_OBSIDIAN);
        assertTrue(map.threatAt(c(1, 1, 0)).isEmpty());
    }

    @Test
    void theValueOfABlockIsHowMuchTheThreatsDrop() {
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle().at(1, 1, 0, 12));
        // Crying obsidian under the spot: 6 (needs its base, half of 12) goes to 0.
        assertEquals(6.0, map.value(List.of(new Placement(c(1, 0, 0), Material.CRYING_OBSIDIAN, ShellReason.BASE))), 1e-9);
        // Obsidian there: the spot becomes real (12) with one ray in three open (4). 6 - 4 = 2.
        assertEquals(2.0, map.value(List.of(new Placement(c(1, 0, 0), Material.OBSIDIAN, ShellReason.SHIELD))), 1e-9);
        assertTrue(map.planned().isEmpty(), "valuing a block does not plan it");
    }

    @Test
    void aCopyPlansOnItsOwn() {
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle());
        ThreatMap copy = map.copy();
        copy.place(c(1, 0, 0), Material.OBSIDIAN);
        assertEquals(BlockKind.OBSIDIAN, copy.kind(c(1, 0, 0)));
        assertEquals(BlockKind.AIR, map.kind(c(1, 0, 0)));
    }

    @Test
    void aBlockCannotBePlannedWhereThereIsOneAlready() {
        ThreatMap map = new ThreatMap(Scenes.open().build(), new FakeOracle());
        assertThrows(IllegalStateException.class, () -> map.place(c(1, -1, 0), Material.OBSIDIAN));
    }

    @Test
    void aSpotOutsideThePlanningBoxIsNoThreat() {
        ThreatMap map = new ThreatMap(Scenes.obsidianFloor().build(), new FakeOracle().at(5, 0, 0, 10));
        assertEquals(Optional.empty(), map.threatAt(c(5, 0, 0)));
    }
}
