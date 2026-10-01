package com.xploits.bench;

import com.xploits.bench.core.CrystalAttackPace;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * The lab's honest city attacker (surround++ spec §9): what a hacked client's auto-city does, inside the rules the
 * server enforces. It mines the wall block of our hole nearest to it (obsidian or crying obsidian, one of the four
 * next to our feet, within {@value #REACH} blocks of its eyes) at {@code mineTicks} a block: {@value #VANILLA_TICKS},
 * vanilla's speed with a netherite pickaxe and Efficiency V, or 0, the worst case, a server that lets it break again at
 * once. Every tick it tells the players around exactly what the server tells them about a real miner
 * ({@code ServerWorld.setBlockBreakingInfo}, verified in the 1.21.11 jar: the {@code BlockBreakingProgressS2CPacket}
 * to every other player within 32 blocks), so a defence that listens for it hears it. The tick the block breaks it puts
 * a crystal in the gap only by the vanilla rule ({@code EndCrystalItem.useOnBlock}: obsidian or bedrock under it, air,
 * nothing in the one-by-two box) and hits it {@link CrystalAttackPace#ATTACK_DELAY} ticks later. Refilled, it mines
 * again. {@link SurroundMiner}, the bench's own, stays as it is for the bench's {@code city} fight.
 *
 * <p>It reads nothing of our modules. Nothing it counts carries a position.
 */
final class LabCityAttack implements Script, FightBehaviour {
    static final int VANILLA_TICKS = LabHeadMiner.MINE_TICKS;
    static final double REACH = LabHeadMiner.REACH;
    private static final List<Direction> SIDES = List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH);

    private final Vec3i anchor;
    private final int mineTicks;
    private BlockPos mining;
    private int progress;
    private EndCrystalEntity crystal;
    private int hitDue = -1;
    private int breaks;
    private int crystals;
    private int noBase;
    private int explosions;
    private int brokenFirst;

    /**
     * @param anchor    where it stands, an offset from F
     * @param mineTicks ticks to mine one block; 0 breaks one every tick a wall is there
     */
    LabCityAttack(Vec3i anchor, int mineTicks) {
        if (mineTicks < 0) throw new IllegalArgumentException("mine ticks " + mineTicks);
        this.anchor = anchor;
        this.mineTicks = mineTicks;
    }

    @Override
    public String name() {
        return "lab-city-attack";
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
        int t = tick.sinceT0();
        if (t <= 0) return;
        ServerWorld world = tick.world();
        if (sparring.isDead() || tick.player().isDead()) {
            stopMining(world, sparring);
            close();
            return;
        }
        if (crystal != null && t >= hitDue) hit(sparring, world);
        BlockPos feet = tick.player().getBlockPos();
        // The block it was mining is gone, replaced by something softer, or no longer one of our walls (we moved).
        if (mining != null && (!mineable(world.getBlockState(mining)) || mining.getY() != feet.getY()
            || mining.getManhattanDistance(feet) != 1)) {
            stopMining(world, sparring);
        }
        if (mining == null) {
            mining = nearestWall(sparring, world, feet);
            progress = 0;
            if (mining == null) return;
        }
        progress++;
        int stage = mineTicks == 0 ? 9 : Math.min(9, progress * 10 / mineTicks);
        world.setBlockBreakingInfo(sparring.getId(), mining, stage);
        if (progress < Math.max(1, mineTicks)) return;
        BlockPos gap = mining;
        stopMining(world, sparring);
        world.setBlockState(gap, Blocks.AIR.getDefaultState());
        breaks++;
        if (crystal == null) placeInGap(world, gap, t);
    }

    private static boolean mineable(BlockState state) {
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.CRYING_OBSIDIAN);
    }

    private static BlockPos nearestWall(Sparring sparring, ServerWorld world, BlockPos feet) {
        BlockPos nearest = null;
        double best = REACH * REACH;
        for (Direction side : SIDES) {
            BlockPos pos = feet.offset(side);
            if (!mineable(world.getBlockState(pos))) continue;
            double d = sparring.getEyePos().squaredDistanceTo(pos.toCenterPos());
            if (d <= best) {
                best = d;
                nearest = pos;
            }
        }
        return nearest;
    }

    /** Stops the packets for the block it was mining, as the server does when a miner gives up or finishes. */
    private void stopMining(ServerWorld world, Sparring sparring) {
        if (mining == null) return;
        world.setBlockBreakingInfo(sparring.getId(), mining, -1);
        mining = null;
    }

    private void placeInGap(ServerWorld world, BlockPos gap, int t) {
        BlockState base = world.getBlockState(gap.down());
        if (!base.isOf(Blocks.OBSIDIAN) && !base.isOf(Blocks.BEDROCK)) {
            noBase++;
            return;
        }
        if (!world.isAir(gap)) return;
        Box box = new Box(gap.getX(), gap.getY(), gap.getZ(), gap.getX() + 1, gap.getY() + 2, gap.getZ() + 1);
        if (!world.getOtherEntities(null, box).isEmpty()) return;
        Vec3d top = Vec3d.ofBottomCenter(gap);
        crystal = new EndCrystalEntity(world, top.x, top.y, top.z);
        crystal.setShowBottom(false);
        world.spawnEntity(crystal);
        crystals++;
        hitDue = t + CrystalAttackPace.ATTACK_DELAY;
    }

    private void hit(Sparring sparring, ServerWorld world) {
        if (crystal.isRemoved()) {
            brokenFirst++;
        } else {
            crystal.damage(world, world.getDamageSources().playerAttack(sparring), 1f);
            explosions++;
        }
        crystal = null;
        hitDue = -1;
    }

    @Override
    public void close() {
        if (crystal != null && !crystal.isRemoved()) crystal.discard();
        crystal = null;
        hitDue = -1;
    }

    /** What it did, for the trace. Server thread. */
    String counts() {
        return breaks + " wall block(s) mined, " + crystals + " crystal(s) put in the gap (" + noBase
            + " gap(s) had no base under them), " + explosions + " set off by it, " + brokenFirst + " broken first";
    }
}
