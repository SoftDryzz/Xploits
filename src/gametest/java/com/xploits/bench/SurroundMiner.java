package com.xploits.bench;

import com.xploits.bench.core.CrystalAttackPace;
import com.xploits.bench.core.MiningSchedule;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * Task A3 ({@code city}): the opponent mines one of our hole's four wall blocks ({@link MiningSchedule}: air
 * after {@value MiningSchedule#CITY_FIRST_BREAK} ticks, then again {@value MiningSchedule#CITY_REBREAK} ticks
 * after our side refills the gap — real crystal-PvP's "city" tactic), and the same tick the block turns to air
 * places an end crystal into the gap (feet level, resting on the obsidian {@link HoleWalls} laid under the
 * wall) and hits it {@value CrystalAttackPace#ATTACK_DELAY} ticks later, reusing A2's own pacing constant and
 * its {@link CrystalAttackPace#shouldAbandon} stop condition rather than re-deriving either.
 *
 * <p>Also a {@link Script}: it stands at its own {@code standAnchor}, facing the player, so no separate mover
 * is needed to compose it into a {@link ComposedFight} (the same dual role task A3 gave {@link CrystalAttack}).
 */
public final class SurroundMiner implements Script, FightBehaviour {
    private final Vec3i standAnchor;
    private final Direction wallSide;
    private final MiningSchedule schedule = new MiningSchedule();

    private boolean everTicked;
    private boolean solidLastTick;
    private EndCrystalEntity crystal;
    private int hitDueTick = -1;

    private int breaches;
    private int refilledBeforeExplosion;
    private int explosions;
    private int brokenFirst;
    private int abandoned;

    /**
     * @param standAnchor where the opponent stands, an offset from F
     * @param wallSide    which of our hole's four wall blocks (around F) it mines
     */
    public SurroundMiner(Vec3i standAnchor, Direction wallSide) {
        this.standAnchor = standAnchor;
        this.wallSide = wallSide;
    }

    @Override
    public String name() {
        return "surround-miner";
    }

    @Override
    public Vec3i anchor() {
        return standAnchor;
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.pad(world, standAnchor, 1);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
        int t = tick.sinceT0();
        if (t < 0) return;

        boolean sparringAlive = !sparring.isDead();
        boolean targetAlive = !tick.player().isDead();
        if (crystal != null && CrystalAttackPace.shouldAbandon(sparringAlive, targetAlive)) abandon();
        if (!sparringAlive || !targetAlive) return;

        if (!everTicked) {
            schedule.arm(t);
            everTicked = true;
            solidLastTick = true; // the wall stands from the start of the fight (HoleWalls' own build)
        }

        BlockPos wallPos = tick.arena().at(Vec3i.ZERO.offset(wallSide));
        boolean solidNow = !tick.world().getBlockState(wallPos).isAir();
        if (solidNow && !solidLastTick) {
            // Refilled since last tick (our own side's defence): the gap survived until now, and the next
            // mine is instant from here.
            if (crystal != null) refilledBeforeExplosion++;
            schedule.arm(t);
        }
        solidLastTick = solidNow;

        if (crystal != null && t >= hitDueTick) hit(tick, sparring);

        if (solidNow && schedule.due(t)) {
            // Safety net: the fixed timing (CITY_REBREAK == ATTACK_DELAY == 2, hit checked before due() every
            // tick) means a previous crystal is always resolved before this can fire again in practice, but a
            // pending one is never silently overwritten if it somehow is not.
            if (crystal != null) abandon();
            mine(tick, sparring, wallPos);
            solidLastTick = false;
        }
    }

    /** Mines the wall block to air and places a crystal into the gap, resting on the obsidian floor beneath
     * it ({@link HoleWalls}); skipped, without consuming the schedule, only if that spot is somehow occupied. */
    private void mine(Tick tick, Sparring sparring, BlockPos wallPos) {
        if (!isFree(tick.world(), wallPos)) return;
        tick.world().setBlockState(wallPos, Blocks.AIR.getDefaultState());
        breaches++;
        Vec3d top = wallPos.toBottomCenterPos();
        EndCrystalEntity c = new EndCrystalEntity(tick.world(), top.x, top.y, top.z);
        c.setShowBottom(false);
        tick.world().spawnEntity(c);
        crystal = c;
        hitDueTick = tick.sinceT0() + CrystalAttackPace.ATTACK_DELAY;
    }

    /** The same occupancy box vanilla's own {@code EndCrystalItem.useOnBlock} checks (task A2's report). */
    private static boolean isFree(ServerWorld world, BlockPos gap) {
        Box box = new Box(gap.getX(), gap.getY(), gap.getZ(), gap.getX() + 1, gap.getY() + 2, gap.getZ() + 1);
        return world.getOtherEntities(null, box).isEmpty();
    }

    private void hit(Tick tick, Sparring sparring) {
        if (crystal.isRemoved()) {
            brokenFirst++;
        } else {
            crystal.damage(tick.world(), tick.world().getDamageSources().playerAttack(sparring), 1f);
            explosions++;
        }
        crystal = null;
        hitDueTick = -1;
    }

    /** Mirrors {@link CrystalAttack}'s own fix-round-1 abandon: the fight ended mid-cycle, so the pending
     * crystal is resolved here instead of exploded — still {@link #brokenFirst()} if our aura had already
     * removed it, otherwise discarded and counted as {@link #abandoned()}. */
    private void abandon() {
        if (crystal.isRemoved()) {
            brokenFirst++;
        } else {
            crystal.discard();
            abandoned++;
        }
        crystal = null;
        hitDueTick = -1;
    }

    @Override
    public void close() {
        if (crystal != null) abandon();
    }

    // --- Counters (task A3's brief: breaches, refills before the explosion, our own breaks first) --------

    /** Wall blocks successfully mined to air so far. Server thread. */
    public int breaches() {
        return breaches;
    }

    /** How many times the gap was refilled by our own side before its crystal exploded. Server thread. */
    public int refilledBeforeExplosion() {
        return refilledBeforeExplosion;
    }

    /** Of the crystals placed into the gap, how many our aura broke before this behaviour's own hit tick (or
     * before the fight ended, if it ended first). Server thread. */
    public int brokenFirst() {
        return brokenFirst;
    }

    /** Explosions this behaviour caused. Server thread. */
    public int explosions() {
        return explosions;
    }

    /** Crystals discarded, unexploded, because the fight ended before their hit tick came.
     * {@code breaches() == explosions() + brokenFirst() + abandoned()} always. Server thread. */
    public int abandoned() {
        return abandoned;
    }
}
