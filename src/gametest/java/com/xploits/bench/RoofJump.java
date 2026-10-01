package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * A jump stopped by a ceiling (0.7.2, crystal-aura++ under a roof): our player jumps in place ({@link RoofJumpMotion})
 * under a 3 by 3 obsidian ceiling whose underside is {@value #CEILING} blocks above our feet, one free block over our
 * head, so every jump stops at the ceiling, as vanilla's own collision would stop it. The sparring stands still
 * {@value #SPARRING_X} blocks out, facing us, on a 3 by 3 obsidian pad whose top is {@value #PAD_TOP} block above our
 * feet: the crystals go on that pad, one block above our feet, and the pad's near edge hides our lower half from them
 * while we stand. So the worst place for us is the top of a jump (closer to them and all in the open), and the top
 * of a jump is the ceiling, not a full jump's height: a self-budget that read only where we stand, or that stopped the
 * jump short of the ceiling, would let through a crystal that takes us under the reserve at the top of a jump. This
 * is the one scenario where our player really is at that stopped height when crystals go off.
 */
public final class RoofJump implements Script {
    /** The ceiling's own layer above our feet block (its underside), and its half width around our start block. */
    static final int CEILING = 3;
    static final int CEILING_RADIUS = 1;
    /** How far out the sparring stands, along +x. */
    static final int SPARRING_X = 4;
    /** How far the pad's top, the sparring's feet, is above ours. */
    static final int PAD_TOP = 1;
    private static final Vec3i ANCHOR = new Vec3i(SPARRING_X, PAD_TOP, 0);

    @Override
    public String name() {
        return "roof-jump";
    }

    @Override
    public Vec3i anchor() {
        return ANCHOR;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.fill(world, -CEILING_RADIUS, CEILING, -CEILING_RADIUS, CEILING_RADIUS, CEILING, CEILING_RADIUS,
            Blocks.OBSIDIAN);
        arena.pad(world, ANCHOR, 1);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
    }

    /** It only turns to face the player (our own player moves, so the run is never static anyway). */
    @Override
    public boolean isStatic() {
        return true;
    }
}
