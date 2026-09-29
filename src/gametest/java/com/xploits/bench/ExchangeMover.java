package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3i;

/**
 * Task A3 ({@code exchange}): the opponent strafes {@value #DISTANCE} blocks out along +x,
 * {@value #SIDE} blocks to each side, on an open obsidian floor that also covers our own standing block — an
 * "open-ground exchange" (plan, Phase A). Movement and geometry only; the attack (A2's {@link CrystalAttack}),
 * autobreak, spot blocking and escape are composed alongside it (see {@link Fights#exchange()}).
 *
 * <p>Interpretation (documented, task A2+ precedent): the brief's "strafing (existing mover)" is read as
 * reusing the existing {@link Shuttler}/{@link Circler} strafing engine, at the distance the brief states for
 * this fight (3 blocks) rather than the committed {@link Strafe} scenario's own fixed 5 — a new small mover
 * built the same way, not a literal reuse of that one class.
 */
final class ExchangeMover extends Shuttler {
    /** How far out the sparring strafes, along +x. */
    static final int DISTANCE = 3;
    /** How far to each side of the line through F it goes. */
    static final int SIDE = 2;

    ExchangeMover() {
        super(new Vec3i(DISTANCE, 0, -SIDE), new Vec3i(DISTANCE, 0, SIDE), leg -> 0, true);
    }

    @Override
    public String name() {
        return "exchange";
    }

    /** A pad one block past the walk on every side, reaching back to cover our own standing block too. */
    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.fill(world, -1, -1, -SIDE - 1, DISTANCE + 1, -1, SIDE + 1, Blocks.OBSIDIAN);
    }
}
