package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * Stands at F+(4,0,0) on the floor (no pad) and attacks: at T0+20 ticks, then every 40, it spawns an end
 * crystal on the attack cell F+(2,-1,1) and, the next tick, hits it with a player attack of its own, so
 * the explosion's cause is the sparring. The arena gives the cell obsidian and air in the two blocks
 * above it.
 */
public final class Attacker implements Script {
    private static final Vec3i ANCHOR = new Vec3i(4, 0, 0);
    /** The obsidian block the crystals stand on. */
    static final Vec3i ATTACK_CELL = new Vec3i(2, -1, 1);
    static final int FIRST_ATTACK = 20;
    static final int ATTACK_EVERY = 40;

    /** No crystal is spawned at or after this tick after T0; {@link Integer#MAX_VALUE} for never. */
    private final int stopTick;
    private EndCrystalEntity crystal;
    private int attacks;

    /** Attacks for the whole run. */
    public Attacker() {
        this(Integer.MAX_VALUE);
    }

    /** Attacks until {@code stopTick} ticks after T0 (no crystal is spawned at or after it). */
    public Attacker(int stopTick) {
        this.stopTick = stopTick;
    }

    @Override
    public String name() {
        return "attacker";
    }

    @Override
    public Vec3i anchor() {
        return ANCHOR;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        int x = ATTACK_CELL.getX();
        int y = ATTACK_CELL.getY();
        int z = ATTACK_CELL.getZ();
        arena.fill(world, x, y, z, x, y, z, Blocks.OBSIDIAN);
        arena.fill(world, x, y + 1, z, x, y + 2, z, Blocks.AIR);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
        int t = tick.sinceT0();
        if (t < FIRST_ATTACK) return;
        int phase = (t - FIRST_ATTACK) % ATTACK_EVERY;
        if (phase == 0 && t < stopTick) {
            Vec3d top = tick.arena().standingAt(ATTACK_CELL.up());
            crystal = new EndCrystalEntity(tick.world(), top.x, top.y, top.z);
            crystal.setShowBottom(false);
            tick.world().spawnEntity(crystal);
            attacks++;
        } else if (phase == 1 && crystal != null) {
            // The player may have broken it already; a removed crystal is left alone.
            if (!crystal.isRemoved()) crystal.damage(tick.world(), tick.world().getDamageSources().playerAttack(sparring), 1f);
            crystal = null;
        }
    }

    /** Crystals spawned so far. Server thread. */
    public int attacks() {
        return attacks;
    }
}
