package com.xploits.bench;

import com.xploits.bench.core.CrystalAttackPace;
import com.xploits.bench.core.SpotBlockPace;
import com.xploits.pvp.crystal.core.ExplosionMath;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.ExplosionImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Task A2+ requirement 2: every {@value SpotBlockPace#BLOCK_EVERY} ticks, places obsidian on the free spot
 * next to the opponent (within {@value SpotBlockPace#REACH}, obsidian/bedrock base, two air blocks above, no
 * entity — the same shape a crystal placement needs) that a crystal would hurt it most from — the spot our
 * aura would most likely use next — measured the same way A2's {@link CrystalAttack} measures its own
 * candidates ({@link ExplosionImpl#calculateReceivedDamage} for the exposure, {@link ExplosionMath#rawDamage}
 * for the raw formula), the highest one chosen by A2's own {@link CrystalAttackPace#chooseCell} (ties keep the
 * first, in the candidate order below: east, west, south, north). Does nothing when it has no free spot within
 * reach that tick.
 *
 * <p>Fix round 1 (review-a3.md finding 2): the candidate spot's support block is read at
 * {@link SpotBlockPace#SUPPORT_BELOW} level(s) below the opponent's own feet block — the opponent's feet
 * block itself is always open air (the arena clears it), so checking it for obsidian/bedrock could never
 * find one, in any fight; {@code exchange}, the only fight that composes this behaviour, went unaware every
 * run before this fix.
 */
public final class SpotBlock implements FightBehaviour {
    private static final List<Direction> SIDES = List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH);

    private int placed;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        if (!SpotBlockPace.dueAt(tick.sinceT0())) return;
        ServerWorld world = tick.world();
        Vec3d at = sparring.getEntityPos();
        BlockPos feet = sparring.getBlockPos();
        List<BlockPos> free = new ArrayList<>();
        List<Double> rawDamage = new ArrayList<>();
        for (Direction side : SIDES) {
            // Fix round 1 (review-a3.md finding 2): the opponent's own feet block is open air (the arena
            // clears it); the candidate spot's support — the block a crystal placed there would rest on — is
            // SpotBlockPace.SUPPORT_BELOW level(s) down, at the floor's own level, not at feet height.
            BlockPos base = feet.down(SpotBlockPace.SUPPORT_BELOW).offset(side);
            if (!isValidSpot(world, base, at)) continue;
            Vec3d top = base.up().toBottomCenterPos();
            double distance = at.distanceTo(top);
            float raw = distance > ExplosionMath.CRYSTAL_RADIUS ? 0f
                : ExplosionMath.rawDamage(distance, ExplosionImpl.calculateReceivedDamage(top, sparring));
            free.add(base);
            rawDamage.add((double) raw);
        }
        if (free.isEmpty()) return;
        BlockPos chosen = free.get(CrystalAttackPace.chooseCell(rawDamage));
        world.setBlockState(chosen.up(), Blocks.OBSIDIAN.getDefaultState());
        placed++;
    }

    /**
     * Within reach, an obsidian or bedrock base, the two blocks above it free (air), and no entity in that
     * box — the same shape A2's {@link CrystalAttack#isFree} checks (vanilla's own {@code EndCrystalItem}
     * placement rule), independently reproduced here since that method is private to A2's file.
     */
    private static boolean isValidSpot(ServerWorld world, BlockPos base, Vec3d opponent) {
        if (opponent.distanceTo(base.up().toBottomCenterPos()) > SpotBlockPace.REACH) return false;
        if (!world.getBlockState(base).isOf(Blocks.OBSIDIAN) && !world.getBlockState(base).isOf(Blocks.BEDROCK)) return false;
        if (!world.getBlockState(base.up()).isAir() || !world.getBlockState(base.up(2)).isAir()) return false;
        Box box = new Box(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
        return world.getOtherEntities(null, box).isEmpty();
    }

    /** Blocks placed so far. Server thread. */
    public int placed() {
        return placed;
    }
}
