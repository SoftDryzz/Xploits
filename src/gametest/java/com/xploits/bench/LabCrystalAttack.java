package com.xploits.bench;

import com.xploits.bench.core.CrystalAttackPace;
import com.xploits.bench.core.CrystalAttackPace.Phase;
import com.xploits.pvp.crystal.core.ExplosionMath;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.explosion.ExplosionImpl;

/**
 * The lab's honest crystal attacker ({@link LabWorstCase}): what a hacked crystal aura does, within the rule the
 * game itself enforces. {@link CrystalAttack}, the bench's own, builds its cells once and then spawns on them whatever
 * stands there later, so against a head-height surround it kept "placing" crystals inside the obsidian; this one never
 * does. Every {@link CrystalAttackPace#ATTACK_EVERY} ticks it looks at every block up to {@value #AROUND} blocks around
 * our player, from {@value #BELOW} below our feet to {@value #ABOVE} above them, that vanilla lets a crystal go on
 * ({@code EndCrystalItem.useOnBlock}, read in the 1.21.11 jar: obsidian or bedrock, air right above it, and no entity
 * in the one-by-two box above it) and that is within {@value #REACH} blocks of its own eyes. It spawns the crystal
 * where it would hurt us most, by vanilla's own damage path (as {@link CrystalAttack}), and not at all when nothing
 * reaches {@value #MIN_RAW} raw damage; it hits the crystal {@link CrystalAttackPace#ATTACK_DELAY} ticks later. It does
 * not spare itself: real auras cap their own damage after armour (about 10), which its netherite brings a crystal a few
 * blocks away well under; a cap on the raw damage, before armour, would refuse every spot near us, and the worst case
 * is an attacker that does not care, with its totems and golden apples. It counts at which height, against our feet, each crystal
 * went, to see where it goes once our head is covered.
 *
 * <p>It reads nothing of our modules: the same attacker faces whichever aura is under test.
 */
final class LabCrystalAttack implements Script, FightBehaviour {
    static final int AROUND = 4;
    static final int BELOW = 2;
    static final int ABOVE = 3;
    static final double REACH = 6.0;
    static final double MIN_RAW = 2.0;

    private final Vec3i anchor;
    private EndCrystalEntity crystal;
    private int spawned;
    private int explosions;
    private int brokenFirst;
    /** Crystals spawned level with our feet, with our head, and higher. */
    private int atFeet;
    private int atHead;
    private int aboveHead;

    LabCrystalAttack(Vec3i anchor) {
        this.anchor = anchor;
    }

    @Override
    public String name() {
        return "lab-crystal-attack";
    }

    @Override
    public Vec3i anchor() {
        return anchor;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        // Nothing: it only uses what the fight leaves standing.
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
        boolean sparringAlive = !sparring.isDead();
        boolean targetAlive = !tick.player().isDead();
        if (crystal != null && CrystalAttackPace.shouldAbandon(sparringAlive, targetAlive)) {
            if (!crystal.isRemoved()) crystal.discard();
            crystal = null;
            return;
        }
        Phase phase = CrystalAttackPace.phaseAt(tick.sinceT0(), sparringAlive, targetAlive);
        if (phase == Phase.SPAWN) spawn(sparring, tick);
        else if (phase == Phase.HIT) hit(sparring, tick);
    }

    private void spawn(Sparring sparring, Tick tick) {
        crystal = null;
        ServerWorld world = tick.world();
        ServerPlayerEntity player = tick.player();
        BlockPos feet = player.getBlockPos();
        Vec3d best = null;
        int bestLevel = 0;
        double bestRaw = MIN_RAW;
        for (int dy = -BELOW; dy <= ABOVE; dy++) {
            for (int dx = -AROUND; dx <= AROUND; dx++) {
                for (int dz = -AROUND; dz <= AROUND; dz++) {
                    BlockPos base = feet.add(dx, dy, dz);
                    BlockState block = world.getBlockState(base);
                    if (!block.isOf(Blocks.OBSIDIAN) && !block.isOf(Blocks.BEDROCK)) continue;
                    BlockPos up = base.up();
                    if (!world.isAir(up)) continue;
                    Box box = new Box(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
                    if (!world.getOtherEntities(null, box).isEmpty()) continue;
                    Vec3d top = Vec3d.ofBottomCenter(up);
                    if (sparring.getEyePos().distanceTo(top) > REACH) continue;
                    double raw = rawTo(player, top);
                    if (raw <= bestRaw) continue;
                    best = top;
                    bestRaw = raw;
                    bestLevel = dy + 1;
                }
            }
        }
        if (best == null) return;
        crystal = new EndCrystalEntity(world, best.x, best.y, best.z);
        crystal.setShowBottom(false);
        world.spawnEntity(crystal);
        spawned++;
        if (bestLevel <= 0) atFeet++;
        else if (bestLevel == 1) atHead++;
        else aboveHead++;
    }

    private static double rawTo(Entity entity, Vec3d at) {
        double distance = entity.getEntityPos().distanceTo(at);
        return distance > ExplosionMath.CRYSTAL_RADIUS ? 0
            : ExplosionMath.rawDamage(distance, ExplosionImpl.calculateReceivedDamage(at, entity));
    }

    private void hit(Sparring sparring, Tick tick) {
        if (crystal == null) return;
        if (crystal.isRemoved()) {
            brokenFirst++;
        } else {
            crystal.damage(tick.world(), tick.world().getDamageSources().playerAttack(sparring), 1f);
            explosions++;
        }
        crystal = null;
    }

    @Override
    public void close() {
        if (crystal != null && !crystal.isRemoved()) crystal.discard();
        crystal = null;
    }

    /** What it did, for the trace. Server thread. */
    String counts() {
        return spawned + " crystal(s): " + atFeet + " at your feet, " + atHead + " at your head, " + aboveHead
            + " above it; " + explosions + " set off by it, " + brokenFirst + " broken first";
    }
}
