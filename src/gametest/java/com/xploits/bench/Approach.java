package com.xploits.bench;

import com.xploits.bench.core.Shuttle;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * The enemy that closes in and backs off (crystal-aura++ R3-5): on a flat obsidian strip along +x, the sparring
 * walks straight at us from {@value #FAR} blocks out to {@value #NEAR} and back, again and again, standing at
 * each end for a pause from {@link Shuttle#IRREGULAR_PAUSES} (0 to 20 ticks, a fixed sequence, so every run is
 * the same). It does not jump. Only the near part of the walk is in crystal range: a crystal must be within 4.5
 * blocks of our feet (Meteor's place and break ranges).
 */
public final class Approach extends Shuttler {
    /** The nearest and farthest the sparring comes, along +x. */
    static final int NEAR = 3;
    static final int FAR = 8;
    /** The strip runs one block past each end and reaches this far to each side of the line through F. */
    static final int HALF_WIDTH = 1;

    public Approach() {
        super(new Vec3i(FAR, 0, 0), new Vec3i(NEAR, 0, 0), Shuttle::irregularPause, false);
    }

    @Override
    public String name() {
        return "approach";
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.fill(world, NEAR - 1, -1, -HALF_WIDTH, FAR + 1, -1, HALF_WIDTH, Blocks.OBSIDIAN);
    }
}
