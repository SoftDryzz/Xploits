package com.xploits.printer.core;

import java.util.Objects;

/**
 * One position as the printer sees it in a tick: what the schematic wants there, what the world (as the server last
 * confirmed it) holds, and whether it is inside the selected placement's enabled sub-region boxes and layer range.
 */
public record Cell(Pos pos, Target target, BlockFacts world, boolean inBoxes) {
    public Cell {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(world, "world");
    }
}
