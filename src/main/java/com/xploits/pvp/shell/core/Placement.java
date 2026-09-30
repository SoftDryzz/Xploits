package com.xploits.pvp.shell.core;

import java.util.Objects;

/** One block to place this tick, and why. */
public record Placement(Cell cell, Material material, ShellReason reason) {
    public Placement {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(reason, "reason");
    }
}
