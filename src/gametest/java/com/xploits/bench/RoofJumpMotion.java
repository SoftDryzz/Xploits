package com.xploits.bench;

import com.xploits.bench.core.CircleWalk;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * OUR player's jumps under {@link RoofJump}'s ceiling (0.7.2): in place, with the same arc and timing as
 * {@link SelfCircleMotion}'s ({@link CircleWalk#jumpHeight}: every {@value CircleWalk#JUMP_EVERY} ticks from tick
 * {@value CircleWalk#FIRST_JUMP}, a parabola {@value CircleWalk#JUMP_HEIGHT} blocks high), but capped where our box
 * meets the ceiling, as vanilla's collision caps a real jump there: the ceiling's height less our box's own height
 * ({@code Entity.getHeight()}, the float the game builds the box from), less {@value #GAP} so the box only ever comes
 * up to the ceiling and never into it (the server refuses a move into a block). Real client movement, the same
 * mechanism as {@link SelfCircleMotion}: only the position and the ground state are set here, every bench tick after
 * T0, and the client's own per-tick packet carries them to the server.
 */
final class RoofJumpMotion implements SelfMotion {
    /** How far below the ceiling the top of our box stops, in blocks: a hair, far below anything the damage can see. */
    static final double GAP = 1e-4;

    @Override
    public void tick(MinecraftClient client, Vec3d origin, int k) {
        ClientPlayerEntity player = client.player;
        if (player == null) throw new BenchException("the client has no player");
        double cap = RoofJump.CEILING - (double) player.getHeight() - GAP;
        player.updatePosition(origin.x, origin.y + Math.min(CircleWalk.jumpHeight(k), cap), origin.z);
        player.setOnGround(!CircleWalk.airborne(k));
    }
}
