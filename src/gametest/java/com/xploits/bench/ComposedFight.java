package com.xploits.bench;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * Task A2+: composes one base {@link Script} — the opponent's name, anchor, geometry and main behaviour (A2's
 * {@link CrystalAttack}, or any of the existing movers) — with any number of {@link FightBehaviour}s that run
 * alongside it every bench tick, in the order given. A3's fight scripts build one of these per fight instead
 * of writing a new {@code Script} class for every combination of behaviours.
 *
 * <p>Never {@link #isStatic}: a composed fight always has at least one behaviour that can still act (autobreak
 * watching, a cooldown about to fire, the escape trigger), so it is never safe to assume nothing more can
 * happen — the same reasoning {@link CrystalAttack} and the other fight scripts already apply.
 */
public final class ComposedFight implements Script {
    private final Script base;
    private final List<FightBehaviour> behaviours;

    public ComposedFight(Script base, List<FightBehaviour> behaviours) {
        this.base = base;
        this.behaviours = List.copyOf(behaviours);
    }

    @Override
    public String name() {
        return base.name();
    }

    @Override
    public Vec3i anchor() {
        return base.anchor();
    }

    @Override
    public Vec3d spawnPoint(Arena arena) {
        return base.spawnPoint(arena);
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        base.build(arena, world);
        for (FightBehaviour behaviour : behaviours) behaviour.build(arena, world);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        base.tick(sparring, tick);
        for (FightBehaviour behaviour : behaviours) behaviour.tick(sparring, tick);
    }

    /** Task A2 fix round 1: delegates to the base script — the only part of a composed fight that can leave
     * something pending (A2's {@link CrystalAttack}); none of the {@link FightBehaviour}s do. */
    @Override
    public void close() {
        base.close();
    }
}
