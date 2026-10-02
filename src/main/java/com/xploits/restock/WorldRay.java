package com.xploits.restock;

import com.xploits.printer.core.Aim;
import com.xploits.printer.core.Face;
import com.xploits.printer.core.Pos;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Positions between the cores and the game, and the vanilla raycast with an exact float rotation. Client thread. */
final class WorldRay {
    private WorldRay() {
    }

    static BlockPos block(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    static Pos pos(BlockPos b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }

    static Direction direction(Face f) {
        return Direction.valueOf(f.name());
    }

    /**
     * The vanilla raycast from the eye with exactly this rotation: the hit when it returns {@code block}'s {@code side}
     * within {@code reach}, null otherwise. Its result is the hit sent with the click.
     */
    static BlockHitResult ray(MinecraftClient mc, Pos block, Face side, Aim.Rotation r, double reach) {
        ClientPlayerEntity p = mc.player;
        ClientWorld world = mc.world;
        if (p == null || world == null) return null;
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(p.getRotationVector(r.pitch(), r.yaw()).multiply(reach + 1.0));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        if (!hit.getBlockPos().equals(block(block)) || hit.getSide() != direction(side)) return null;
        if (hit.getPos().distanceTo(eye) > reach) return null;
        return hit;
    }
}
