package com.xploits.bench;

import com.xploits.bench.core.CrystalAttackPace;
import com.xploits.bench.core.CrystalAttackPace.Phase;
import com.xploits.pvp.crystal.core.ExplosionMath;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.explosion.ExplosionImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Task A2: a reusable server-side attack behaviour for a {@link Script} — an opponent that attacks with
 * crystals, like a real client — modelled on {@link Attacker}: it spawns an end crystal, then hits it one or
 * more ticks later with a player attack of its own, so the explosion's cause is the sparring. Unlike
 * {@link Attacker}'s single fixed cell, this behaves at the invulnerability pace
 * ({@link CrystalAttackPace#ATTACK_EVERY} ticks, starting {@link CrystalAttackPace#FIRST_ATTACK} ticks after
 * T0) and, {@link CrystalAttackPace#ATTACK_DELAY} ticks after each spawn — like a client with ping — picks the
 * {@code candidateCells} (offsets from F, script-provided: FEET-mode scripts place them around our feet, HEAD-
 * mode ones at our head level for face-placing into a hole) whose explosion would deal our player the most raw
 * damage right now, computed server-side with vanilla's own damage path ({@link ExplosionImpl#calculateReceivedDamage}
 * for the exposure, {@link ExplosionMath#rawDamage} for the raw formula — the same pair {@code CrystalAuraPlusPlus}
 * already verifies against the yarn jar). The pacing, the stop condition (either combatant dead) and the cell
 * choice itself (highest raw damage, ties keep the first) are the pure {@link CrystalAttackPace}; this class is
 * only the server-side wiring: building the cells, measuring the candidates, spawning, and hitting.
 *
 * <p>A candidate cell is skipped this cycle when its crystal's box (the two air blocks above the cell, exactly
 * as vanilla's own {@code EndCrystalItem.useOnBlock} checks it) already holds an entity — including one of our
 * own standing crystals (task A2 requirement 1, "never places into our own crystals' cells") — so it is never
 * chosen; when every candidate is occupied, nothing is spawned that cycle. If our own aura broke the spawned
 * crystal first (it is {@link EndCrystalEntity#isRemoved()} by the hit tick), nothing more happens that cycle
 * beyond counting it.
 *
 * <p>Fairness (task A2 requirement 4): this class never reads any crystal-aura module's state, settings or
 * budget — only vanilla world/entity state and the candidate cells it was built with — so the same behaviour
 * faces whichever aura is under test.
 */
public final class CrystalAttack implements Script {
    /** Which level {@code candidateCells} was built for (task A2 requirement 1); purely descriptive — the
     * geometry itself is entirely the caller's choice of offsets. */
    public enum Mode {
        /** Cells at our feet level, around us. */
        FEET,
        /** Cells at our head level, for face-placing into a hole. */
        HEAD
    }

    private final Mode mode;
    private final Vec3i anchor;
    private final List<Vec3i> candidateCells;

    private EndCrystalEntity crystal;
    private int spawned;
    private int brokenFirst;
    private int explosions;

    /**
     * @param mode           descriptive only (see {@link Mode}); logged in {@link #name()}
     * @param anchor         the script's anchor block, an offset from F ({@link Script#anchor()})
     * @param candidateCells the cells to place on, offsets from F, in the order ties break by
     * @throws BenchException {@code candidateCells} is empty
     */
    public CrystalAttack(Mode mode, Vec3i anchor, List<Vec3i> candidateCells) {
        if (candidateCells.isEmpty()) throw new BenchException("crystal-attack needs at least one candidate cell");
        this.mode = mode;
        this.anchor = anchor;
        this.candidateCells = List.copyOf(candidateCells);
    }

    @Override
    public String name() {
        return "crystal-attack-" + mode.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Vec3i anchor() {
        return anchor;
    }

    /** Every candidate cell: obsidian on the block, air on the two above it (the crystal's box). */
    @Override
    public void build(Arena arena, ServerWorld world) {
        for (Vec3i cell : candidateCells) {
            int x = cell.getX();
            int y = cell.getY();
            int z = cell.getZ();
            arena.fill(world, x, y, z, x, y, z, Blocks.OBSIDIAN);
            arena.fill(world, x, y + 1, z, x, y + 2, z, Blocks.AIR);
        }
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        sparring.face(tick.player());
        Phase phase = CrystalAttackPace.phaseAt(tick.sinceT0(), !sparring.isDead(), !tick.player().isDead());
        if (phase == Phase.SPAWN) {
            spawn(sparring, tick);
        } else if (phase == Phase.HIT) {
            hit(sparring, tick);
        }
    }

    /** Measures every free candidate cell and spawns on the one that would hurt us most; skips the cycle
     * entirely when every candidate cell is occupied. */
    private void spawn(Sparring sparring, Tick tick) {
        crystal = null;
        ServerWorld world = tick.world();
        ServerPlayerEntity player = tick.player();
        Arena arena = tick.arena();
        List<Vec3d> free = new ArrayList<>();
        List<Double> rawDamage = new ArrayList<>();
        for (Vec3i cell : candidateCells) {
            if (!isFree(world, arena, cell)) continue;
            Vec3d top = arena.standingAt(cell.up());
            double distance = player.getEntityPos().distanceTo(top);
            float raw = distance > ExplosionMath.CRYSTAL_RADIUS ? 0f
                : ExplosionMath.rawDamage(distance, ExplosionImpl.calculateReceivedDamage(top, player));
            free.add(top);
            rawDamage.add((double) raw);
        }
        if (free.isEmpty()) return;
        Vec3d top = free.get(CrystalAttackPace.chooseCell(rawDamage));
        crystal = new EndCrystalEntity(world, top.x, top.y, top.z);
        crystal.setShowBottom(false);
        world.spawnEntity(crystal);
        spawned++;
    }

    /** The spot the crystal would spawn at (the cell's two air blocks) is the box vanilla's own
     * {@code EndCrystalItem.useOnBlock} checks for placing: free when no entity is in it. */
    private static boolean isFree(ServerWorld world, Arena arena, Vec3i cell) {
        BlockPos pos = arena.at(cell.up());
        Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);
        return world.getOtherEntities(null, box).isEmpty();
    }

    private void hit(Sparring sparring, Tick tick) {
        if (crystal == null) return; // nothing was spawned this cycle: every candidate cell was occupied
        // The player (our own aura) may have broken it already; a removed crystal is left alone.
        if (crystal.isRemoved()) {
            brokenFirst++;
        } else {
            crystal.damage(tick.world(), tick.world().getDamageSources().playerAttack(sparring), 1f);
            explosions++;
        }
        crystal = null;
    }

    // --- Counters (task A2 requirement 2) -----------------------------------------------------------

    /** Crystals spawned so far. Server thread. */
    public int spawned() {
        return spawned;
    }

    /** Of the crystals spawned, how many our aura broke before the hit tick. Server thread. */
    public int brokenFirst() {
        return brokenFirst;
    }

    /** Explosions this behaviour caused. Server thread. */
    public int explosions() {
        return explosions;
    }
}
