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
    /**
     * The smallest hit worth acting on: the planner's line for a spot's weighted value, and the breaker's for a crystal's
     * damage. At 1, because a crystal every half second at about 1.5, what the lab's attacker put on a head cell a head
     * miner had opened, outpaces any regeneration, and a line of 2 left that cell open while it cost totems. A spot that
     * still needs its base is weighed against it at {@link ThreatMap#NEEDS_BASE_WEIGHT} of its damage: it counts from 8.
     */
    public static final double MIN_DANGER = 1.0;

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
