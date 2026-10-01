package com.xploits.pvp.shell.core;

/** What a cell holds, as far as crystals and blocks care (surround++ spec §3, verified in the 1.21.11 jar). */
public enum BlockKind {
    /** Air: the only kind an end crystal can be placed into ({@code EndCrystalItem.useOnBlock}: {@code isAir} above the base). */
    AIR,
    /** Not air, but a block can be placed into it (grass, a snow layer, water): no crystal goes in it. */
    REPLACEABLE,
    OBSIDIAN,
    /** Hardness 50 and blast resistance 1200 like obsidian, and not a crystal base. */
    CRYING_OBSIDIAN,
    BEDROCK,
    COBWEB,
    /** Any other block. */
    OTHER,
    /** Outside the snapshot: never a spot, a base, a support or a place to put a block. */
    UNKNOWN;

    /** An end crystal can be placed on it: obsidian or bedrock. */
    public boolean isBase() {
        return this == OBSIDIAN || this == BEDROCK;
    }

    /** A block can be placed into it. */
    public boolean isPlaceable() {
        return this == AIR || this == REPLACEABLE;
    }

    /** It gives a placed block a face to be placed against. */
    public boolean supports() {
        return this == OBSIDIAN || this == CRYING_OBSIDIAN || this == BEDROCK || this == OTHER;
    }

    /** Mined in obsidian's time or never: a wall worth standing in. */
    public boolean isHard() {
        return this == OBSIDIAN || this == CRYING_OBSIDIAN || this == BEDROCK;
    }
}
