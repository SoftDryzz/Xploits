package com.xploits.bench;

import com.xploits.bench.core.AutobreakRange;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Task A2+ requirement 1: each tick, breaks any end crystal within {@value AutobreakRange#AUTOBREAK_RANGE}
 * of the opponent that is not its own — like a real client's autobreak, this also protects a crystal it is
 * mid-cycle on placing for its own attack.
 *
 * <p>Fix round 1 (review-a2.md, Important #3): "its own" is read by identity — the exact entity A2's
 * {@link CrystalAttack#pendingCrystal()} is tracking, when this behaviour is given that attack — never by
 * matching a candidate cell's position. Position matching wrongly protected ANY crystal standing on one of
 * those cells, including a foreign one (e.g. our own aura's) that happened to land there; identity cannot make
 * that mistake, since a foreign crystal is never the same Java object as the attack's own pending one, whatever
 * position it is at.
 */
public final class Autobreak implements FightBehaviour {
    /** {@code null}: no crystal near the opponent is ever treated as its own. */
    private final CrystalAttack ownAttack;
    private int broken;

    /** No crystal near the opponent is ever treated as its own (it runs no attack of its own alongside this). */
    public Autobreak() {
        this(null);
    }

    /** @param ownAttack the opponent's own attack running alongside this behaviour, if any: its
     *                   {@link CrystalAttack#pendingCrystal()} is never broken. {@code null} if it runs none. */
    public Autobreak(CrystalAttack ownAttack) {
        this.ownAttack = ownAttack;
    }

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        ServerWorld world = tick.world();
        Vec3d at = sparring.getEntityPos();
        double reach = AutobreakRange.AUTOBREAK_RANGE;
        Box box = new Box(at.x - reach, at.y - reach, at.z - reach, at.x + reach, at.y + reach, at.z + reach);
        EndCrystalEntity own = ownAttack == null ? null : ownAttack.pendingCrystal();
        for (EndCrystalEntity crystal : world.getEntitiesByClass(EndCrystalEntity.class, box, Entity::isAlive)) {
            double distance = at.distanceTo(crystal.getEntityPos());
            if (AutobreakRange.shouldBreak(distance, crystal == own)) {
                crystal.damage(world, world.getDamageSources().playerAttack(sparring), 1f);
                broken++;
            }
        }
    }

    /** Crystals broken so far. Server thread. */
    public int broken() {
        return broken;
    }
}
