package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * The enemy above (crystal-aura++ R3-5): an obsidian platform whose top is {@value #HEIGHT} blocks above our
 * feet, {@value #NEAR} to {@value #FAR} blocks out along +x and {@value #HALF_WIDTH} blocks to each side; the
 * sparring walks across it, sideways to us, {@value #WALK} blocks each way from the middle, {@value #WALK_X}
 * blocks out, pausing {@value #PAUSE} ticks at each end and jumping as {@link Circler} does. Crystals go on the
 * platform's near row, one block from the sparring and 3.6 to 4.1 blocks from our feet (Meteor's place and
 * break ranges are 4.5); the platform shields us from them.
 */
public final class Above extends Shuttler {
    /** How far the platform's top, the sparring's feet, is above ours. */
    static final int HEIGHT = 3;
    /** The platform's nearest and farthest rows along +x. */
    static final int NEAR = 2;
    static final int FAR = 4;
    /** The platform reaches this far to each side of the line through F. */
    static final int HALF_WIDTH = 3;
    /** The row the sparring walks along. */
    static final int WALK_X = 3;
    /** How far each side of the middle it walks. */
    static final int WALK = 2;
    static final int PAUSE = 10;

    public Above() {
        super(new Vec3i(WALK_X, HEIGHT, -WALK), new Vec3i(WALK_X, HEIGHT, WALK), leg -> PAUSE, true);
    }

    @Override
    public String name() {
        return "above";
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.fill(world, NEAR, HEIGHT - 1, -HALF_WIDTH, FAR, HEIGHT - 1, HALF_WIDTH, Blocks.OBSIDIAN);
    }
}
