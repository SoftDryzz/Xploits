package com.xploits.printer.core;

/** What the schematic wants at a position (printer spec §3, C3): unknown, air, or a block. Unknown is never air. */
public record Target(Kind kind, BlockFacts block) {
    public enum Kind { UNKNOWN, AIR, BLOCK }

    public static final Target UNKNOWN = new Target(Kind.UNKNOWN, null);
    public static final Target AIR = new Target(Kind.AIR, null);

    public Target {
        if ((kind == Kind.BLOCK) != (block != null)) throw new IllegalArgumentException("only a block target has a block");
        if (block != null && block.air()) throw new IllegalArgumentException("an air target is Target.AIR");
    }

    public static Target of(BlockFacts block) {
        return block.air() ? AIR : new Target(Kind.BLOCK, block);
    }
}
