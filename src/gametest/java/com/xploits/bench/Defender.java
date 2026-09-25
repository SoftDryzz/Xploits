package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * Stands in a hole facing the player: a 3 by 3 obsidian pad around the anchor, and obsidian walls at
 * feet level on its four sides. The anchor is F+(5,0,0); {@code profile-defensive} puts it at F+(4,0,0)
 * on purpose (SURROUNDED needs the target within 5.0).
 */
public final class Defender implements Script {
    /** The MEASURE anchor's distance along +x. */
    public static final int DEFAULT_DISTANCE = 5;

    private final Vec3i anchor;

    public Defender() {
        this(DEFAULT_DISTANCE);
    }

    /** A Defender at F+(distance,0,0). */
    public Defender(int distance) {
        this.anchor = new Vec3i(distance, 0, 0);
    }

    @Override
    public String name() {
        return "defender";
    }

    @Override
    public Vec3i anchor() {
        return anchor;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.pad(world, anchor, 1);
        int x = anchor.getX();
        int y = anchor.getY();
        int z = anchor.getZ();
        arena.fill(world, x - 1, y, z, x - 1, y, z, Blocks.OBSIDIAN);
        arena.fill(world, x + 1, y, z, x + 1, y, z, Blocks.OBSIDIAN);
        arena.fill(world, x, y, z - 1, x, y, z - 1, Blocks.OBSIDIAN);
        arena.fill(world, x, y, z + 1, x, y, z + 1, Blocks.OBSIDIAN);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
    }
}
