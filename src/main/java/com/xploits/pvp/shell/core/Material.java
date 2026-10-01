package com.xploits.pvp.shell.core;

/** The blocks surround++ places (spec §5.4): never an ender chest, which is mined in under half the time. */
public enum Material {
    OBSIDIAN(BlockKind.OBSIDIAN),
    CRYING_OBSIDIAN(BlockKind.CRYING_OBSIDIAN);

    private final BlockKind kind;

    Material(BlockKind kind) {
        this.kind = kind;
    }

    /** What the cell holds once it is placed. */
    public BlockKind kind() {
        return kind;
    }
}
