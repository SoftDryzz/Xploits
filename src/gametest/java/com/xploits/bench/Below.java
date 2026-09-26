package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * The enemy below (crystal-aura++ R3-5): an open pit whose obsidian floor is {@value #DEPTH} blocks below our
 * feet, {@value #NEAR} to {@value #FAR} blocks out along +x and {@value #HALF_WIDTH} blocks to each side, with
 * blast-proof walls (reinforced deepslate) that end level with our floor, so the sparring's head and shoulders
 * stay in sight. The sparring walks across the pit, sideways to us, {@value #WALK} blocks each way from the
 * middle, {@value #WALK_X} blocks out, pausing {@value #PAUSE} ticks at each end and jumping as {@link Circler}
 * does. Crystals go on the pit's floor, one block from the sparring and 3.6 to 4.1 blocks from our feet
 * (Meteor's place and break ranges, walls ranges included, are 4.5); the pit's near wall shields us from them.
 */
public final class Below extends Shuttler {
    /** How far the pit's floor, the sparring's feet, is below ours. */
    static final int DEPTH = 3;
    /** The pit's nearest and farthest rows along +x. */
    static final int NEAR = 2;
    static final int FAR = 4;
    /** The pit reaches this far to each side of the line through F. */
    static final int HALF_WIDTH = 3;
    /** The row the sparring walks along. */
    static final int WALK_X = 3;
    /** How far each side of the middle it walks. */
    static final int WALK = 2;
    static final int PAUSE = 10;

    public Below() {
        super(new Vec3i(WALK_X, -DEPTH, -WALK), new Vec3i(WALK_X, -DEPTH, WALK), leg -> PAUSE, true);
    }

    @Override
    public String name() {
        return "below";
    }

    /** Walls one block thick around the pit, down to the floor's level, then the pit's air and its floor. */
    @Override
    public void build(Arena arena, ServerWorld world) {
        int floor = -DEPTH - 1;
        arena.fill(world, NEAR - 1, floor, -HALF_WIDTH - 1, FAR + 1, -1, HALF_WIDTH + 1, Blocks.REINFORCED_DEEPSLATE);
        arena.fill(world, NEAR, -DEPTH, -HALF_WIDTH, FAR, -1, HALF_WIDTH, Blocks.AIR);
        arena.fill(world, NEAR, floor, -HALF_WIDTH, FAR, floor, HALF_WIDTH, Blocks.OBSIDIAN);
    }
}
