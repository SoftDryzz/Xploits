package com.xploits.bench;

import com.xploits.bench.core.Shuttle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * OUR player's own strafe (crystal-aura++ R3-14, self-strafe): zig-zags {@value #SIDE} blocks each way across
 * the line to the sparring (the z axis, as every other fight situation's geometry), at {@link Circler#SPEED}
 * blocks/s ({@link Shuttler#SPEED_PER_TICK}), turning at once at each end (no pause, {@link Shuttle}), never
 * jumping. Real client movement, the same mechanism as {@link SelfCircleMotion}: only the position is set here,
 * every bench tick after T0, and the client's own normal per-tick packet carries it to the server.
 */
final class SelfStrafeMotion implements SelfMotion {
    /** How far each way it strafes, along z. */
    static final int SIDE = 2;

    private final Shuttle shuttle = new Shuttle(2.0 * SIDE, Shuttler.SPEED_PER_TICK, leg -> 0);

    @Override
    public void tick(MinecraftClient client, Vec3d origin, int k) {
        ClientPlayerEntity player = client.player;
        if (player == null) throw new BenchException("the client has no player");
        double z = origin.z - SIDE + shuttle.offset(k);
        player.updatePosition(origin.x, origin.y, z);
        player.setOnGround(true);
    }
}
