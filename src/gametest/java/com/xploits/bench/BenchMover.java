package com.xploits.bench;

import com.xploits.printer.core.BaritoneSession;
import com.xploits.restock.Mover;
import com.xploits.restock.core.RestockReason;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * The bench's scripted walker (the seam that stands in for Baritone, which the bench does not have). It walks the player
 * in a straight line over the flat arena floor, {@value #STEP} blocks a tick (under vanilla's walking speed, far under the
 * server's "moved too quickly"), by moving the client's own player as the bench's self-motions do
 * ({@code Entity.updatePosition} during restock's {@code TickEvent.Pre}, before the tick's movement packet carries it).
 * It keeps the player's height, so a goal on another level is never reached and restock's stall watch gives it up, as
 * with a goal Baritone cannot reach. Scenes keep containers off its straight lines. Client thread only.
 */
final class BenchMover implements Mover {
    static final double STEP = 0.2;

    private final MinecraftClient mc;
    private Vec3d goal;
    private int goals;

    BenchMover(MinecraftClient mc) {
        this.mc = mc;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Optional<RestockReason.Refusal> begin(String prefix, BaritoneSession.Mode mode) {
        return Optional.empty();
    }

    @Override
    public boolean goTo(BlockPos feet) {
        goal = Vec3d.ofBottomCenter(feet);
        goals++;
        return true;
    }

    @Override
    public boolean goToward(int x, int z) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;
        goal = new Vec3d(x + 0.5, p.getY(), z + 0.5);
        goals++;
        return true;
    }

    @Override
    public void cancel() {
        goal = null;
    }

    @Override
    public boolean idle() {
        return goal == null;
    }

    @Override
    public void tick() {
        ClientPlayerEntity p = mc.player;
        if (goal == null || p == null) return;
        Vec3d at = p.getEntityPos();
        double dx = goal.x - at.x;
        double dz = goal.z - at.z;
        double d = Math.hypot(dx, dz);
        if (d <= STEP) p.updatePosition(goal.x, at.y, goal.z);
        else p.updatePosition(at.x + dx * STEP / d, at.y, at.z + dz * STEP / d);
    }

    @Override
    public boolean end() {
        goal = null;
        return true;
    }

    @Override
    public String prefix() {
        return "";
    }

    /** Goals given since the session began. */
    int goals() {
        return goals;
    }
}
