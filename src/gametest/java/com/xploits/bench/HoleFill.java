package com.xploits.bench;

import com.xploits.bench.core.HoleFillPace;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Task A2+ requirement 4: every {@value HoleFillPace#HOLE_FILL_EVERY} ticks, fills the nearest free 1x1 hole
 * (a missing floor block, at the real player's own floor level) within {@value HoleFillPace#HOLE_FILL_RADIUS}
 * blocks of the player that the player is not standing in and that no entity occupies, so there is nowhere
 * left to step into — the plan's "fills a free hole next to us so we cannot step in".
 */
public final class HoleFill implements FightBehaviour {
    private int filled;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        if (!HoleFillPace.dueAt(tick.sinceT0())) return;
        ServerWorld world = tick.world();
        ServerPlayerEntity player = tick.player();
        BlockPos playerFeet = player.getBlockPos();
        BlockPos standingOn = playerFeet.down();
        Vec3d at = player.getEntityPos();
        int r = (int) Math.ceil(HoleFillPace.HOLE_FILL_RADIUS);

        List<BlockPos> free = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos candidate = standingOn.add(dx, 0, dz);
                if (candidate.equals(standingOn)) continue;
                Vec3d centre = candidate.toCenterPos();
                double distance = at.distanceTo(centre);
                if (distance > HoleFillPace.HOLE_FILL_RADIUS) continue;
                if (!isFreeHole(world, candidate)) continue;
                free.add(candidate);
                distances.add(distance);
            }
        }
        if (free.isEmpty()) return;
        BlockPos hole = free.get(HoleFillPace.nearestHole(distances));
        world.setBlockState(hole, Blocks.OBSIDIAN.getDefaultState());
        filled++;
    }

    /** A missing floor tile (air) with no entity in the space right above it. */
    private static boolean isFreeHole(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) return false;
        Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        return world.getOtherEntities(null, box).isEmpty();
    }

    /** Holes filled so far. Server thread. */
    public int filled() {
        return filled;
    }
}
