package com.xploits.pvp.shell.core;

/**
 * A spot where an opponent could put an end crystal that would hurt us (surround++ spec §5.1).
 *
 * @param spot   the air cell the crystal would stand in
 * @param kind   whether its base is already there
 * @param damage the damage to us after armour, as measured with the world as it is
 * @param open   the share of its rays to us that no block planned this tick closes, 0 to 1
 */
public record Threat(Cell spot, Kind kind, double damage, double open) {
    /** A hit this big outpaces what a player heals between two crystals: below it a spot is not worth a block. */
    public static final double MIN_DANGER = 2.0;

    public enum Kind {
        /** Obsidian or bedrock under it now. */
        REAL,
        /** Its base cell can take a block the opponent places first: one more action, one more chance for us. */
        NEEDS_BASE
    }

    public static double weightOf(Kind kind) {
        return kind == Kind.REAL ? 1.0 : ThreatMap.NEEDS_BASE_WEIGHT;
    }

    public double weight() {
        return weightOf(kind);
    }

    /** What the planner ranks by: the damage, times how likely the spot is to be used, times how much of it gets through. */
    public double weighted() {
        return damage * weight() * open;
    }
}
