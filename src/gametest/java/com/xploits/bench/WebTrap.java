package com.xploits.bench;

import com.xploits.bench.core.WebTrapPace;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Task A2+ requirement 5: on two independent cooldowns, obstructs the real player — every
 * {@value WebTrapPace#WEB_EVERY} ticks, a cobweb at the player's feet block if it is free (air); every
 * {@value WebTrapPace#TRAP_EVERY} ticks, a head trap (obsidian on the block above the player's head, plus the
 * four blocks around the head at head height) on whichever of those five positions are free. Like real
 * auto-web/auto-trap, only positions that are already air get a block: nothing already solid is overwritten.
 */
public final class WebTrap implements FightBehaviour {
    private static final List<Direction> AROUND_HEAD = List.of(
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

    private int webs;
    private int trapBlocks;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        int t = tick.sinceT0();
        if (!WebTrapPace.webDueAt(t) && !WebTrapPace.trapDueAt(t)) return;
        ServerWorld world = tick.world();
        ServerPlayerEntity player = tick.player();
        BlockPos feet = player.getBlockPos();

        if (WebTrapPace.webDueAt(t) && world.getBlockState(feet).isAir()) {
            world.setBlockState(feet, Blocks.COBWEB.getDefaultState());
            webs++;
        }
        if (WebTrapPace.trapDueAt(t)) {
            BlockPos head = feet.up();
            trapIfFree(world, head.up());
            for (Direction side : AROUND_HEAD) trapIfFree(world, head.offset(side));
        }
    }

    private void trapIfFree(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) return;
        world.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
        trapBlocks++;
    }

    /** Cobwebs placed so far. Server thread. */
    public int webs() {
        return webs;
    }

    /** Trap blocks placed so far (up to 5 a cooldown). Server thread. */
    public int trapBlocks() {
        return trapBlocks;
    }
}
