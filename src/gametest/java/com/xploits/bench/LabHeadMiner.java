package com.xploits.bench;

import net.minecraft.block.BlockState;
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
 * attackers. Whether a server lets it mine faster (instant rebreak) is not proven for 6b6t, so this one does not. Crying obsidian is mined too (same
 * hardness), and every tick it sends the progress packet the server sends about a real miner ({@code ServerWorld.setBlockBreakingInfo}), so a defence
 * that listens for it hears it.
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
        if (mining != null && (!hard(world.getBlockState(mining)) || mining.getSquaredDistance(head) > 1.0)) {
            stop(world, sparring);
        }
        if (mining == null) {
            BlockPos nearest = null;
            double best = REACH * REACH;
            for (Direction side : SIDES) {
                BlockPos pos = head.offset(side);
                if (!hard(world.getBlockState(pos))) continue;
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
        progress++;
        world.setBlockBreakingInfo(sparring.getId(), mining, Math.min(9, progress * 10 / MINE_TICKS));
        if (progress >= MINE_TICKS) {
            BlockPos broken = mining;
            stop(world, sparring);
            world.setBlockState(broken, Blocks.AIR.getDefaultState());
            mined++;
        }
    }

    private static boolean hard(BlockState state) {
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.CRYING_OBSIDIAN);
    }

    private void stop(ServerWorld world, Sparring sparring) {
        if (mining == null) return;
        world.setBlockBreakingInfo(sparring.getId(), mining, -1);
        mining = null;
    }

    /** Head blocks broken so far. Server thread. */
    int mined() {
        return mined;
    }
}
