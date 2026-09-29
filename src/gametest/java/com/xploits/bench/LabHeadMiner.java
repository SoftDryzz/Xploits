package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * The lab's answer to a covered head ({@link LabWorstCase}): what a hacked client's auto-mine does when the victim
 * puts obsidian at head height (research, 2026-09-30: BlackOut's AutoMine tries that block first, the "CEV" target,
 * before plain surround mining). It picks the obsidian next to our head nearest to itself, within {@value #REACH}
 * blocks, and mines it at vanilla speed for a netherite pickaxe with Efficiency V, {@value #MINE_TICKS} ticks; if the
 * block is gone or replaced before that, it starts again. Once it breaks, the head cell is open for the crystal
 * attackers. Whether a server lets it mine faster (instant rebreak) is not proven for 6b6t, so this one does not.
 */
final class LabHeadMiner implements FightBehaviour {
    static final int MINE_TICKS = 43;
    static final double REACH = 4.5;
    private static final List<Direction> SIDES = List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH);

    private BlockPos mining;
    private int progress;
    private int mined;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        if (tick.sinceT0() <= 0 || sparring.isDead()) return;
        ServerWorld world = tick.world();
        BlockPos head = tick.player().getBlockPos().up();
        // The block it was mining is gone, replaced by something else, or no longer next to our head (we moved).
        if (mining != null && (!world.getBlockState(mining).isOf(Blocks.OBSIDIAN) || mining.getSquaredDistance(head) > 1.0)) {
            mining = null;
        }
        if (mining == null) {
            BlockPos nearest = null;
            double best = REACH * REACH;
            for (Direction side : SIDES) {
                BlockPos pos = head.offset(side);
                if (!world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) continue;
                double d = sparring.getEyePos().squaredDistanceTo(pos.toCenterPos());
                if (d <= best) {
                    best = d;
                    nearest = pos;
                }
            }
            if (nearest == null) return;
            mining = nearest;
            progress = 0;
        }
        if (++progress >= MINE_TICKS) {
            world.setBlockState(mining, Blocks.AIR.getDefaultState());
            mined++;
            mining = null;
        }
    }

    /** Head blocks broken so far. Server thread. */
    int mined() {
        return mined;
    }
}
