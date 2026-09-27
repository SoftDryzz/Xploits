package com.xploits.bench;

import com.xploits.bench.core.CircleWalk;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * OUR player's own circle (crystal-aura++ R3-14, self-circle): walks {@link CircleWalk}'s circle of radius
 * {@value CircleWalk#RADIUS} around our start block ({@code origin}), at {@value CircleWalk#SPEED} blocks/s,
 * jumping the same way as the sparring's own {@link Circler} (mirrors its constants). The circle never passes
 * through {@code origin} itself (it walks its rim), so the first active tick already stands {@value
 * CircleWalk#RADIUS} blocks off it; that one tick's jump is well inside the server's own movement check (a
 * single packet is only rejected past 10 blocks, verified in the yarn 1.21.11 jar,
 * {@code ServerPlayNetworkHandler.onPlayerMove}: {@code moved too quickly} at a squared distance over 100).
 *
 * <p><b>Real client movement, verified in the yarn 1.21.11 jar.</b> {@code ClientPlayerEntity.tick()} always
 * calls its private {@code sendMovementPackets()} once a tick (when not riding), which compares the entity's
 * own x/y/z, angles and ground state against what was last sent ({@code lastXClient} et al.) and sends a
 * {@code PlayerMoveC2SPacket} accordingly: it needs nothing from us but the entity's own state, so setting
 * {@code Entity.updatePosition} and {@code Entity.setOnGround} here, every bench tick, is exactly what a real
 * player's own movement leaves behind for that packet to carry. {@code updatePosition} (not {@code
 * refreshPositionAndAngles},
 * which is for a teleport: it also resets the render-interpolation state) is the one used for an ordinary
 * per-tick move; it recomputes the hitbox too ({@code Entity.setPosition}, which it calls). During a jump arc
 * the client's own physics (gravity, ground collision) can still nudge Y or on-ground within the same tick
 * after this runs, so a raycast is not guaranteed to see our feet at exactly {@code x}/{@code y}/{@code z}; what
 * is guaranteed is that it is the same for both auras, since Meteor's CrystalAura and crystal-aura++ raycast the
 * same entity state on the same tick.
 */
final class SelfCircleMotion implements SelfMotion {
    @Override
    public void tick(MinecraftClient client, Vec3d origin, int k) {
        ClientPlayerEntity player = client.player;
        if (player == null) throw new BenchException("the client has no player");
        double x = origin.x + CircleWalk.offsetX(k);
        double y = origin.y + CircleWalk.jumpHeight(k);
        double z = origin.z + CircleWalk.offsetZ(k);
        player.updatePosition(x, y, z);
        player.setOnGround(!CircleWalk.airborne(k));
    }
}
