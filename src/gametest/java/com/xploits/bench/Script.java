package com.xploits.bench;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * What a {@link Sparring} does (spec {@code 2026-09-25-ingame-bench}, §Scripts and geometry): where it
 * stands, what it needs built, and its behaviour once per bench tick. Offsets are relative to F, the
 * player's feet block, with +x the sparring's side. Everything here runs on the server thread.
 */
public interface Script {
    /** The name used in the bench's own messages. */
    String name();

    /** The script's anchor block, as an offset from F. */
    Vec3i anchor();

    /** Where the sparring spawns: by default on the anchor block's bottom centre. */
    default Vec3d spawnPoint(Arena arena) {
        return arena.standingAt(anchor());
    }

    /** Builds what the script needs (its pad, walls or cell), before the sparring spawns. */
    void build(Arena arena, ServerWorld world);

    /** One bench tick, after the sparring's own countdowns and effects. */
    void tick(Sparring sparring, Tick tick);

    /**
     * Whether the script never moves the sparring (crystal-aura++ R3-6): only then may a measured run end once
     * nothing can happen any more ({@link com.xploits.bench.core.Settle}). False unless a script says so
     * itself; it is never inferred.
     */
    default boolean isStatic() {
        return false;
    }

    /**
     * Task A2 fix round 1 (review-a2.md, Important #2): resolves anything the script left pending — called
     * exactly once, whether the run ends normally, the target dies (which {@link #tick} can still see and
     * react to itself, since the sparring stays alive), or the sparring itself dies. The last case is why this
     * exists as its own hook rather than being left to {@link #tick}: {@code Sparring.step} only calls
     * {@code tick} while the sparring is alive, checked before that same tick's own death could have just
     * happened, so a script's {@code tick} can never be the one to notice its own combatant just died — proven
     * in-game (review-a2.md fix round 1 probe B: a pending crystal was left live, spawned but never resolved,
     * when the sparring died mid-cycle, exactly because {@code tick} was never called again to see it).
     * {@code Sparring} calls this both promptly (the tick the sparring's own death is first observed) and, as
     * a safety net, at teardown (any other reason a run stops with something still pending, e.g. the time
     * limit) — safe to call twice, and most scripts leave nothing pending and do not override it.
     */
    default void close() {
    }

    /**
     * The state a script sees each tick. {@code sinceT0} is -1 before T0, then 1 on the first tick after
     * it; a script waits for T0 before it moves or attacks.
     */
    record Tick(ServerWorld world, ServerPlayerEntity player, Arena arena, int sinceT0) {
    }
}
