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
     * The state a script sees each tick. {@code sinceT0} is -1 before T0, then 1 on the first tick after
     * it; a script waits for T0 before it moves or attacks.
     */
    record Tick(ServerWorld world, ServerPlayerEntity player, Arena arena, int sinceT0) {
    }
}
