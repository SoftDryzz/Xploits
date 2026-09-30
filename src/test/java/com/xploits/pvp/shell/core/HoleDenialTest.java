package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Filling the opponents' holes (surround++ spec §7.2). */
class HoleDenialTest {
    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    private static ShellSnapshot.Builder hole(ShellSnapshot.Builder s, int x, int z) {
        return s.block(x, -1, z, BlockKind.AIR).block(x, -2, z, BlockKind.OTHER).block(x + 1, -1, z, BlockKind.OBSIDIAN)
            .block(x - 1, -1, z, BlockKind.OBSIDIAN).block(x, -1, z + 1, BlockKind.OBSIDIAN).block(x, -1, z - 1, BlockKind.OBSIDIAN);
    }

    private static Optional<Cell> choose(ShellSnapshot s) {
        return HoleDenial.choose(new ThreatMap(s, new FakeOracle()));
    }

    @Test
    void aHoleNextToAnOpponentIsFilled() {
        // The opponent stands at x 4.5; the hole's centre is 1 block from him and 3.67 from our eyes.
        ShellSnapshot s = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 3, 0).hostile(new Vec(4.5, 0, 0.5)).build();
        assertEquals(Optional.of(c(3, -1, 0)), choose(s));
    }

    @Test
    void theHoleNearestToAnOpponentGoesFirst() {
        // (3,-1,0) is 1 from him; (2,-1,2) is sqrt(4 + 4) = 2.83.
        ShellSnapshot s = hole(hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 2, 2), 3, 0).hostile(new Vec(4.5, 0, 0.5)).build();
        assertEquals(Optional.of(c(3, -1, 0)), choose(s));
    }

    @Test
    void aHoleRightNextToUsIsNeverFilled() {
        ShellSnapshot s = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 1, 0).hostile(new Vec(2.5, 0, 0.5)).build();
        assertEquals(Optional.empty(), choose(s));
    }

    @Test
    void aHoleFarFromEveryOpponentIsLeftAlone() {
        ShellSnapshot s = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 3, 0).hostile(new Vec(-3.5, 0, 0.5)).build();
        assertEquals(Optional.empty(), choose(s));
    }

    @Test
    void aHoleSomeoneStandsInIsLeftAlone() {
        ShellSnapshot s = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 3, 0).hostile(new Vec(4.5, 0, 0.5)).occupied(c(3, -1, 0)).build();
        assertEquals(Optional.empty(), choose(s));
    }

    @Test
    void offWithTheSettingOff() {
        ShellSettings off = new ShellSettings(2, true, true, false, true, false, 6);
        ShellSnapshot s = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 3, 0).hostile(new Vec(4.5, 0, 0.5)).settings(off).build();
        assertEquals(Optional.empty(), choose(s));
    }
}
