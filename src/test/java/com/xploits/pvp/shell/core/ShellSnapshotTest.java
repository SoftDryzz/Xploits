package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One tick of the world around us, relative to our feet block (surround++ spec §4.1). */
class ShellSnapshotTest {
    @Test
    void aCentredPlayerIsTwoCellsTall() {
        ShellSnapshot s = ShellSnapshot.builder().build();
        assertEquals(Set.of(new Cell(0, 0, 0), new Cell(0, 1, 0)), s.body());
        assertFalse(s.sticksOut());
    }

    @Test
    void aPlayerNearTheEdgeOfItsBlockOverlapsTheNextOne() {
        ShellSnapshot s = ShellSnapshot.builder().feet(new Vec(0.9, 0, 0.5)).build();
        // 0.9 minus and plus 0.3 spans x 0.6 to 1.2: cells 0 and 1.
        assertEquals(Set.of(new Cell(0, 0, 0), new Cell(1, 0, 0), new Cell(0, 1, 0), new Cell(1, 1, 0)), s.body());
        assertTrue(s.sticksOut());
    }

    @Test
    void aPlayerHalfABlockUpSpansThreeCells() {
        ShellSnapshot s = ShellSnapshot.builder().feet(new Vec(0.5, 0.5, 0.5)).build();
        // From 0.5 to 2.3 high: cells 0, 1 and 2.
        assertEquals(Set.of(new Cell(0, 0, 0), new Cell(0, 1, 0), new Cell(0, 2, 0)), s.body());
        assertEquals(0, s.feetLevel());
        assertEquals(2, s.headLevel());
    }

    @Test
    void aCellInsideTheBoxWithNothingGivenIsAirAndOutsideItIsUnknown() {
        ShellSnapshot s = ShellSnapshot.builder().build();
        assertEquals(BlockKind.AIR, s.kind(new Cell(5, 4, -5)));
        assertEquals(BlockKind.UNKNOWN, s.kind(new Cell(6, 0, 0)));
        assertEquals(BlockKind.UNKNOWN, s.kind(new Cell(0, -3, 0)));
        assertEquals(BlockKind.UNKNOWN, s.kind(new Cell(0, 5, 0)));
    }

    @Test
    void ourOwnBodyOccupiesItsCellsButIsNotSomeoneElse() {
        ShellSnapshot s = ShellSnapshot.builder().occupied(new Cell(2, 0, 0)).build();
        assertTrue(s.occupied(new Cell(0, 1, 0)));
        assertFalse(s.occupiedByOthers(new Cell(0, 1, 0)));
        assertTrue(s.occupiedByOthers(new Cell(2, 0, 0)));
    }

    @Test
    void theEyesAreAPlayersEyeHeightAboveTheFeet() {
        assertEquals(new Vec(0.5, 1.62, 0.5), ShellSnapshot.builder().build().eye());
    }

    @Test
    void aWebInOurBodyIsAWebWeAreIn() {
        assertTrue(ShellSnapshot.builder().block(0, 1, 0, BlockKind.COBWEB).build().inWeb());
        assertFalse(ShellSnapshot.builder().block(1, 1, 0, BlockKind.COBWEB).build().inWeb());
    }

    @Test
    void aHostileGivenByItsFeetHasItsEyesAPlayersEyeHeightHigher() {
        ShellSnapshot s = ShellSnapshot.builder().hostile(new Vec(3.5, 0, 0.5)).build();
        assertEquals(List.of(new Vec(3.5, 1.62, 0.5)), s.hostileEyes());
        assertEquals(List.of(new Vec(3.5, 0, 0.5)), s.hostileFeet());
    }

    @Test
    void aHealthThatIsNotANumberIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ShellSnapshot.builder().health(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> ShellSnapshot.builder().health(-1).build());
    }

    @Test
    void aMiningStageOutsideZeroToNineIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ShellSnapshot.builder().mining(new Cell(1, 0, 0), 10).build());
        assertThrows(IllegalArgumentException.class, () -> ShellSnapshot.builder().mining(new Cell(1, 0, 0), -1).build());
    }

    @Test
    void feetOutsideTheirOwnBlockAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ShellSnapshot.builder().feet(new Vec(1.0, 0, 0.5)).build());
        assertThrows(IllegalArgumentException.class, () -> ShellSnapshot.builder().feet(new Vec(0.5, -0.1, 0.5)).build());
    }

    @Test
    void theFloorFillsTheLayerUnderTheFeetAcrossTheBox() {
        ShellSnapshot s = ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).build();
        assertEquals(BlockKind.OBSIDIAN, s.kind(new Cell(-5, -1, 5)));
        assertEquals(BlockKind.AIR, s.kind(new Cell(0, -2, 0)));
    }
}
