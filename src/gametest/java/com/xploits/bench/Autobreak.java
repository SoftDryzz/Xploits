package com.xploits.bench;

import com.xploits.bench.core.AutobreakRange;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Task A2+ requirement 1: each tick, breaks any end crystal within {@value AutobreakRange#AUTOBREAK_RANGE}
 * of the opponent that is not its own — like a real client's autobreak, this also protects a crystal it is
 * mid-cycle on placing for its own attack. "Its own" is never read from A2's {@link CrystalAttack} (this
 * class must not touch its private state, and does not need to): the caller composing this behaviour already
 * knows which cells its own attack, if any, was built with (the same {@code candidateCells} list it passed to
 * {@code CrystalAttack}'s constructor), and passes the same list here — a crystal standing exactly on one of
 * those cells' positions is treated as the opponent's own, whatever spawned it.
 */
public final class Autobreak implements FightBehaviour {
    /** Own-cell position matching tolerance: crystals spawn exactly on a cell's centre, never this far off. */
    private static final double OWN_CELL_TOLERANCE_SQUARED = 1e-3;

    private final List<Vec3i> ownAttackCells;
    private int broken;

    /** No crystal near the opponent is ever treated as its own (it runs no attack of its own alongside this). */
    public Autobreak() {
        this(List.of());
    }

    /**
     * @param ownAttackCells the cell offsets (from F) the opponent's own attack, if any, places crystals on —
     *                       the same list the caller built that attack with
     */
    public Autobreak(List<Vec3i> ownAttackCells) {
        this.ownAttackCells = List.copyOf(ownAttackCells);
    }

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        ServerWorld world = tick.world();
        Vec3d at = sparring.getEntityPos();
        double reach = AutobreakRange.AUTOBREAK_RANGE;
        Box box = new Box(at.x - reach, at.y - reach, at.z - reach, at.x + reach, at.y + reach, at.z + reach);
        List<Vec3d> ownPositions = ownCellPositions(tick.arena());
        for (EndCrystalEntity crystal : world.getEntitiesByClass(EndCrystalEntity.class, box, Entity::isAlive)) {
            Vec3d crystalPos = crystal.getEntityPos();
            double distance = at.distanceTo(crystalPos);
            boolean ownCrystal = ownPositions.stream().anyMatch(p -> p.squaredDistanceTo(crystalPos) < OWN_CELL_TOLERANCE_SQUARED);
            if (AutobreakRange.shouldBreak(distance, ownCrystal)) {
                crystal.damage(world, world.getDamageSources().playerAttack(sparring), 1f);
                broken++;
            }
        }
    }

    private List<Vec3d> ownCellPositions(Arena arena) {
        List<Vec3d> positions = new ArrayList<>();
        for (Vec3i cell : ownAttackCells) positions.add(arena.standingAt(cell.up()));
        return positions;
    }

    /** Crystals broken so far. Server thread. */
    public int broken() {
        return broken;
    }
}
