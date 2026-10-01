package com.xploits.pvp.shell.core;

import java.util.List;

/**
 * A block relative to the player's feet block (surround++ spec §4.1): never an absolute position. x and z are the
 * world's axes (x east, z south), y is up.
 */
public record Cell(int x, int y, int z) {
    public Cell plus(int dx, int dy, int dz) {
        return new Cell(x + dx, y + dy, z + dz);
    }

    public Cell up() {
        return plus(0, 1, 0);
    }

    public Cell down() {
        return plus(0, -1, 0);
    }

    /** The four horizontal neighbours: east, west, south, north. */
    public List<Cell> sides() {
        return List.of(plus(1, 0, 0), plus(-1, 0, 0), plus(0, 0, 1), plus(0, 0, -1));
    }

    /** The six neighbours: the four sides, then above and below. */
    public List<Cell> neighbours() {
        return List.of(plus(1, 0, 0), plus(-1, 0, 0), plus(0, 0, 1), plus(0, 0, -1), up(), down());
    }

    /** Where an end crystal standing in this cell explodes: the centre of the cell's bottom face (vanilla spawns it there). */
    public Vec explosion() {
        return new Vec(x + 0.5, y, z + 0.5);
    }

    public Vec centre() {
        return new Vec(x + 0.5, y + 0.5, z + 0.5);
    }
}
