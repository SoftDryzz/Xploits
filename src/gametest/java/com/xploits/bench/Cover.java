package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * Real cover for the self-budget (task B2 fix round 1): our player stands under a low ceiling next to a pillar,
 * the sparring in the open on the same pad {@link Still} uses. The ceiling is a slab of obsidian whose underside
 * is {@value #CEILING} blocks above our feet (a jump would go into it), one block around our start block; the
 * pillar is {@value #PILLAR_HEIGHT} blocks tall, {@value #PILLAR_X} blocks out along +x, between us and the
 * crystals. So the budget meets cover that hides some of the reach points and blocks others outright, and
 * the exposure it reads there is neither everywhere 1.0 nor everywhere 0. The sparring only turns to face us.
 */
public final class Cover implements Script {
    private static final Vec3i ANCHOR = new Vec3i(5, 0, 0);
    /** The ceiling's own layer above our feet block, and its half width around it. */
    static final int CEILING = 2;
    static final int CEILING_RADIUS = 1;
    static final int PILLAR_X = 2;
    static final int PILLAR_HEIGHT = 3;

    @Override
    public String name() {
        return "cover";
    }

    @Override
    public Vec3i anchor() {
        return ANCHOR;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.pad(world, ANCHOR, 1);
        arena.fill(world, -CEILING_RADIUS, CEILING, -CEILING_RADIUS, CEILING_RADIUS, CEILING, CEILING_RADIUS,
            Blocks.OBSIDIAN);
        arena.fill(world, PILLAR_X, 0, 0, PILLAR_X, PILLAR_HEIGHT - 1, 0, Blocks.OBSIDIAN);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
    }

    /** It only turns to face the player. */
    @Override
    public boolean isStatic() {
        return true;
    }
}
