package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * Stands where an {@link Attacker} does, F+(4,0,0) on the floor, but only threatens: at T0+20 ticks it
 * spawns one end crystal on the attack cell F+(2,-1,1) and never breaks it. Every 40 ticks after that it
 * looks again, and spawns a new one if its crystal is gone. A crystal standing next to the player keeps
 * {@code PlayerUtils.possibleHealthReductions()} above zero on the client, so auto-pvp's posture stays
 * THREATENED. The arena gives the cell obsidian and air in the two blocks above it.
 */
public final class Menacer implements Script {
    private static final Vec3i ANCHOR = new Vec3i(4, 0, 0);
    static final int FIRST_CRYSTAL = Attacker.FIRST_ATTACK;
    static final int CHECK_EVERY = Attacker.ATTACK_EVERY;

    private EndCrystalEntity crystal;
    private int spawned;

    @Override
    public String name() {
        return "menacer";
    }

    @Override
    public Vec3i anchor() {
        return ANCHOR;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        Vec3i cell = Attacker.ATTACK_CELL;
        int x = cell.getX();
        int y = cell.getY();
        int z = cell.getZ();
        arena.fill(world, x, y, z, x, y, z, Blocks.OBSIDIAN);
        arena.fill(world, x, y + 1, z, x, y + 2, z, Blocks.AIR);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
        int t = tick.sinceT0();
        if (t < FIRST_CRYSTAL || (t - FIRST_CRYSTAL) % CHECK_EVERY != 0) return;
        if (crystal != null && !crystal.isRemoved()) return;
        Vec3d top = tick.arena().standingAt(Attacker.ATTACK_CELL.up());
        crystal = new EndCrystalEntity(tick.world(), top.x, top.y, top.z);
        crystal.setShowBottom(false);
        tick.world().spawnEntity(crystal);
        spawned++;
    }

    /** Crystals spawned so far (more than one means the first was broken). Server thread. */
    public int spawned() {
        return spawned;
    }
}
