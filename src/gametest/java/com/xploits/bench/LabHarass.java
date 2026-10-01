package com.xploits.bench;

import com.xploits.bench.core.PeriodicTrigger;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The lab's worst-case harassment ({@link LabWorstCase}): what a hacked client's auto-web and trap do while its
 * friends crystal you. Every {@value #WEB_EVERY} ticks it webs our feet block and head block wherever they are
 * air, so a web that is broken or left comes back almost at once; every {@value #ROOF_EVERY} ticks it puts
 * obsidian on the block above our head, so jumping out of a hole is not free either. Like real auto-web and
 * auto-trap, only air gets a block: nothing solid is overwritten. {@link WebTrap} is the gentler one the bench's
 * own fights use, and stays as it is so their numbers keep meaning the same.
 */
final class LabHarass implements FightBehaviour {
    static final int WEB_EVERY = 20;
    static final int ROOF_EVERY = 40;

    private int webs;
    private int roofs;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        int t = tick.sinceT0();
        boolean web = PeriodicTrigger.dueAt(t, WEB_EVERY);
        boolean roof = PeriodicTrigger.dueAt(t, ROOF_EVERY);
        if (!web && !roof) return;
        ServerWorld world = tick.world();
        BlockPos feet = tick.player().getBlockPos();
        if (web) {
            if (placeIfAir(world, feet, true)) webs++;
            if (placeIfAir(world, feet.up(), true)) webs++;
        }
        if (roof && placeIfAir(world, feet.up(2), false)) roofs++;
    }

    private static boolean placeIfAir(ServerWorld world, BlockPos pos, boolean cobweb) {
        if (!world.getBlockState(pos).isAir()) return false;
        world.setBlockState(pos, (cobweb ? Blocks.COBWEB : Blocks.OBSIDIAN).getDefaultState());
        return true;
    }

    /** Cobwebs placed so far. Server thread. */
    int webs() {
        return webs;
    }

    /** Roof blocks placed so far. Server thread. */
    int roofs() {
        return roofs;
    }
}
