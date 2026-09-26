package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * The enemy that strafes (crystal-aura++ R3-5): {@value #DISTANCE} blocks out along +x on a flat obsidian pad,
 * the sparring zig-zags sideways across our line of sight, {@value #SIDE} blocks to each side, turning at once
 * at each end, jumping as {@link Circler} does. Crystals go on the pad's near row, within 4.5 blocks of our
 * feet (Meteor's place and break ranges), so the ends of the walk are only reached from the nearest spots.
 */
public final class Strafe extends Shuttler {
    /** How far out the sparring strafes, along +x. */
    static final int DISTANCE = 5;
    /** How far to each side of the line through F it goes. */
    static final int SIDE = 3;

    public Strafe() {
        super(new Vec3i(DISTANCE, 0, -SIDE), new Vec3i(DISTANCE, 0, SIDE), leg -> 0, true);
    }

    @Override
    public String name() {
        return "strafe";
    }

    /** A pad one block past the walk on every side. */
    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.fill(world, DISTANCE - 1, -1, -SIDE - 1, DISTANCE + 1, -1, SIDE + 1, Blocks.OBSIDIAN);
    }
}
