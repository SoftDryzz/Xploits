package com.xploits.bench;

import com.xploits.bench.core.SelfSurroundPace;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Task A2+ requirement 3: once our crystals start hurting the opponent (its first damage taken, from
 * {@link Sparring#stats}'s {@code damageTaken} — the only source of damage to it in a fight is our own
 * aura, the opponent never hurts itself), it fills the four horizontal sides of its own feet block with
 * obsidian, {@value SelfSurroundPace#SURROUND_BLOCKS_PER_TICK} a tick (re-placing a broken one), never the
 * block below it. The sides are checked in a fixed order (east, west, south, north); a missing/broken one
 * is placed nearest to first in that order, one a tick, via {@link SelfSurroundPace#nextMissingSide}.
 */
public final class SelfSurround implements FightBehaviour {
    private static final List<Direction> SIDES = List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH);

    private int placed;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        if (sparring.stats(tick.sinceT0()).damageTaken() <= 0) return;
        ServerWorld world = tick.world();
        BlockPos feet = sparring.getBlockPos();
        List<Boolean> present = new ArrayList<>();
        for (Direction side : SIDES) present.add(world.getBlockState(feet.offset(side)).isOf(Blocks.OBSIDIAN));
        int missing = SelfSurroundPace.nextMissingSide(present);
        if (missing < 0) return;
        world.setBlockState(feet.offset(SIDES.get(missing)), Blocks.OBSIDIAN.getDefaultState());
        placed++;
    }

    /** Blocks placed (or re-placed) so far. Server thread. */
    public int placed() {
        return placed;
    }
}
