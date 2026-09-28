package com.xploits.bench;

import net.minecraft.server.world.ServerWorld;

/**
 * Task A2+: something the fight opponent does alongside its main script — autobreak, spot blocking,
 * self-surround, hole fill, webs, trap, escape — each its own small, switchable unit (a pure decision in
 * {@code com.xploits.bench.core}, unit-tested, plus this thin server-side action), so a fight's script can
 * compose any mix of them next to A2's {@link CrystalAttack} without any of them knowing about the others.
 * {@link ComposedFight} is the ready-made composite A3's fight scripts can use instead of writing a new
 * {@link Script} for every combination.
 */
public interface FightBehaviour {
    /** Builds whatever this behaviour needs before the sparring spawns. Most behaviours build nothing. */
    default void build(Arena arena, ServerWorld world) {
    }

    /** One bench tick, after the composed script's own. */
    void tick(Sparring sparring, Script.Tick tick);
}
