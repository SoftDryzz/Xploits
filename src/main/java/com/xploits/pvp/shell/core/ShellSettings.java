package com.xploits.pvp.shell.core;

/**
 * surround++'s settings (spec §8), as the core reads them.
 *
 * @param blocksPerTick     most blocks placed in one tick (anticheats count them)
 * @param useCryingObsidian use crying obsidian where plain obsidian would be a new crystal base
 * @param moveToHole        walk into a better hole when exposed and threatened
 * @param denyHoles         fill the holes next to the opponents
 * @param breakCrystals     break an opponent's crystal next to us when that is safe
 * @param burrow            turn Meteor's burrow on once under heavy danger in a hole
 * @param reach             how far the opponents are assumed to reach with a crystal, in blocks
 */
public record ShellSettings(int blocksPerTick, boolean useCryingObsidian, boolean moveToHole, boolean denyHoles,
                            boolean breakCrystals, boolean burrow, double reach) {
    public static final int MIN_BLOCKS_PER_TICK = 1;
    public static final int MAX_BLOCKS_PER_TICK = 8;
    public static final int DEFAULT_BLOCKS_PER_TICK = 2;
    public static final double DEFAULT_REACH = 6.0;
    public static final ShellSettings DEFAULTS =
        new ShellSettings(DEFAULT_BLOCKS_PER_TICK, true, true, true, true, false, DEFAULT_REACH);

    public ShellSettings {
        if (blocksPerTick < MIN_BLOCKS_PER_TICK || blocksPerTick > MAX_BLOCKS_PER_TICK) {
            throw new IllegalArgumentException("blocks per tick " + blocksPerTick);
        }
        if (!Double.isFinite(reach) || reach <= 0) throw new IllegalArgumentException("reach " + reach);
    }
}
