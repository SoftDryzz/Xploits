package com.xploits.bench;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * Task A3 fix round 1 (review-a3.md finding 1): a stationary, non-attacking opponent standing on an obsidian
 * pad, facing the player — {@code near-death}'s (no-totem) target. Built passive on purpose: with the
 * opponent's own {@link CrystalAttack} removed, the only way this fight's win can happen is our own aura
 * choosing to place or break a crystal against it — exactly what B0's finishing blow needs to measure. An
 * attacking opponent let our aura's own ordinary foreign-crystal defence (breaking the incoming attack, which
 * still explodes and, at this range, killed the opponent too) win the fight as a side effect, with nothing for
 * our aura to decide; a passive opponent takes that shortcut away. The obsidian pad matters as much as the
 * passivity: without one nearby, our aura has nowhere valid to place a crystal at all (vanilla's own
 * obsidian/bedrock-only placement rule, task A2's report) — the same pad {@link Still}/{@link Circler} already
 * give their own (attacking-us) targets, reused here for a target that never attacks.
 */
final class PassiveTarget implements Script {
    private final Vec3i anchor;

    PassiveTarget(Vec3i anchor) {
        this.anchor = anchor;
    }

    @Override
    public String name() {
        return "passive-target";
    }

    @Override
    public Vec3i anchor() {
        return anchor;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.pad(world, anchor, 1);
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
