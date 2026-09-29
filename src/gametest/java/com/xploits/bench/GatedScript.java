package com.xploits.bench;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * Task B0b: a script that waits, only facing the player, until it is released (the near-death moment), and
 * from then on runs {@code base} as if T0 were the moment: {@code base} sees {@code sinceT0} counted from the
 * release. The {@code near-death-totem} opponent attacks with its own crystals, which at full health would
 * decide the fight (and kill it) inside the warm-up, before our aura has had a chance to land a hit; gated,
 * the warm-up is a quiet first exchange and the attack starts at the moment, on its own usual pace. It also
 * stands on an obsidian pad, like {@link PassiveTarget}'s target, so our aura can place next to it.
 */
final class GatedScript implements Script {
    private final Script base;
    /** The first tick {@code base} ran at, as this run's {@code sinceT0}, minus one; -1 while gated. */
    private int releasedAt = -1;
    private boolean released;

    GatedScript(Script base) {
        this.base = base;
    }

    /** Lets {@code base} act from the next tick on. Server thread. */
    void release() {
        released = true;
    }

    /** The wrapped script, read-only (for counter logging after a run, see {@link Fights}). */
    Script base() {
        return base;
    }

    @Override
    public String name() {
        return base.name();
    }

    @Override
    public Vec3i anchor() {
        return base.anchor();
    }

    @Override
    public Vec3d spawnPoint(Arena arena) {
        return base.spawnPoint(arena);
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        base.build(arena, world);
        // The obsidian pad {@link PassiveTarget} gives its target too: without a block to place on next to it,
        // our aura has nowhere to hit it during the warm-up, and the fight would be decided by its own attack.
        arena.pad(world, base.anchor(), 1);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        if (!released) {
            sparring.face(tick.player());
            return;
        }
        if (releasedAt < 0) releasedAt = tick.sinceT0() - 1;
        base.tick(sparring, new Tick(tick.world(), tick.player(), tick.arena(), tick.sinceT0() - releasedAt));
    }

    @Override
    public void close() {
        base.close();
    }
}
