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

    /**
     * Task A3: resolves anything this behaviour left pending across ticks — the {@link Script#close()} this
     * interface's own {@code tick} signature already mirrors, called by {@link ComposedFight#close()} on
     * every behaviour in its list, not only its base script. Needed once a behaviour can itself leave
     * something pending (A2's {@link CrystalAttack}, which task A3 also made a {@link FightBehaviour} so
     * {@code exchange} can compose it alongside a different base that provides movement): without this,
     * {@link ComposedFight#close()} forwarding only to its base would never reach a {@code CrystalAttack}
     * composed as a behaviour instead. Most behaviours leave nothing pending and do not override it.
     */
    default void close() {
    }
}
