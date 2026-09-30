package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Holes, and which one to walk into (surround++ spec §7.1). */
class HolesTest {
    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    /** A hole one block down at (x,-1,z) in a deepslate floor: walls of this kind, a floor under it. */
    private static ShellSnapshot.Builder hole(ShellSnapshot.Builder s, int x, int z, BlockKind walls) {
        return s.block(x, -1, z, BlockKind.AIR).block(x, -2, z, BlockKind.OTHER)
            .block(x + 1, -1, z, walls).block(x - 1, -1, z, walls).block(x, -1, z + 1, walls).block(x, -1, z - 1, walls);
    }

    private static ShellSnapshot.Builder open() {
        return ShellSnapshot.builder().floor(BlockKind.OTHER);
    }

    @Test
    void aHoleHasAirToStandInHeadroomAFloorAndFourHardWalls() {
        assertTrue(Holes.isHole(hole(open(), 2, 0, BlockKind.OBSIDIAN).build(), c(2, -1, 0)));
        assertFalse(Holes.isHole(hole(open(), 2, 0, BlockKind.OBSIDIAN).block(3, -1, 0, BlockKind.OTHER).build(), c(2, -1, 0)),
            "a wall that is not hard");
        assertFalse(Holes.isHole(hole(open(), 2, 0, BlockKind.OBSIDIAN).block(2, 0, 0, BlockKind.OTHER).build(), c(2, -1, 0)),
            "no headroom");
        assertFalse(Holes.isHole(hole(open(), 2, 0, BlockKind.OBSIDIAN).block(2, -2, 0, BlockKind.AIR).build(), c(2, -1, 0)),
            "no floor");
    }

    @Test
    void inTheOpenAndThreatenedYouWalkToAHoleOneBlockDown() {
        assertEquals(Optional.of(c(2, -1, 0)), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).build(), 5, false));
    }

    @Test
    void notWhenNothingThreatensYouEnough() {
        ShellSnapshot s = hole(open(), 2, 0, BlockKind.OBSIDIAN).build();
        assertEquals(Optional.empty(), Holes.target(s, 3.9, false));
        assertEquals(Optional.of(c(2, -1, 0)), Holes.target(s, 4.0, false));
    }

    @Test
    void notWhileYouPressAKeyNorInAWebNorInTheAir() {
        assertEquals(Optional.empty(), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).build(), 5, true));
        assertEquals(Optional.empty(), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).block(0, 0, 0, BlockKind.COBWEB).build(), 5, false));
        assertEquals(Optional.empty(), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).onGround(false).build(), 5, false));
    }

    @Test
    void notWhenAlreadyInAHole() {
        ShellSnapshot s = hole(open(), 2, 0, BlockKind.OBSIDIAN)
            .block(1, 0, 0, BlockKind.OBSIDIAN).block(-1, 0, 0, BlockKind.OBSIDIAN).block(0, 0, 1, BlockKind.OBSIDIAN)
            .block(0, 0, -1, BlockKind.OBSIDIAN).build();
        assertTrue(Holes.inHole(s));
        assertEquals(Optional.empty(), Holes.target(s, 5, false));
    }

    @Test
    void notWhenAlreadyInAHoleEvenIfAnotherIsOpenDiagonally() {
        // The walls of our own hole block the straight walks; a hole one step away diagonally is not blocked by them.
        ShellSnapshot s = hole(hole(open(), 1, 1, BlockKind.OBSIDIAN), 3, 0, BlockKind.OBSIDIAN)
            .block(1, 0, 0, BlockKind.OBSIDIAN).block(-1, 0, 0, BlockKind.OBSIDIAN).block(0, 0, 1, BlockKind.OBSIDIAN)
            .block(0, 0, -1, BlockKind.OBSIDIAN).build();
        assertTrue(Holes.inHole(s));
        assertEquals(Optional.empty(), Holes.target(s, 5, false));
    }

    @Test
    void notWithTheSettingOff() {
        ShellSettings off = new ShellSettings(2, true, false, true, true, false, 6);
        assertEquals(Optional.empty(), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).settings(off).build(), 5, false));
    }

    @Test
    void notPastThreeBlocks() {
        assertEquals(Optional.empty(), Holes.target(hole(open(), 4, 0, BlockKind.OBSIDIAN).build(), 5, false));
    }

    @Test
    void notThroughABlock() {
        assertEquals(Optional.empty(), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).block(1, 1, 0, BlockKind.OTHER).build(), 5, false));
    }

    @Test
    void aHoleWithSomeoneInItIsNotAPlaceToGo() {
        assertEquals(Optional.empty(), Holes.target(hole(open(), 2, 0, BlockKind.OBSIDIAN).occupied(c(2, -1, 0)).build(), 5, false));
    }

    @Test
    void bedrockWallsWinOverNearerObsidianOnes() {
        // (2,-1,0): obsidian, 2 away. (-3,-1,0): one bedrock wall, 3 away.
        ShellSnapshot s = hole(hole(open(), 2, 0, BlockKind.OBSIDIAN), -3, 0, BlockKind.OBSIDIAN).block(-4, -1, 0, BlockKind.BEDROCK).build();
        assertEquals(Optional.of(c(-3, -1, 0)), Holes.target(s, 5, false));
    }

    @Test
    void withTheSameBedrockFewerSpotsOnTheWallsWin() {
        // (2,-1,0) has obsidian walls with air on top: four spots at your head once in. (-2,-1,0) has crying obsidian: none.
        ShellSnapshot s = hole(hole(open(), 2, 0, BlockKind.OBSIDIAN), -2, 0, BlockKind.CRYING_OBSIDIAN).build();
        assertEquals(Optional.of(c(-2, -1, 0)), Holes.target(s, 5, false));
    }
}
