package com.xploits.bench;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/** Stands at F+(5,0,0) on a 3 by 3 obsidian pad, facing the player. */
public final class Still implements Script {
    private static final Vec3i ANCHOR = new Vec3i(5, 0, 0);

    @Override
    public String name() {
        return "still";
    }

    @Override
    public Vec3i anchor() {
        return ANCHOR;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.pad(world, ANCHOR, 1);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
    }
}
