package com.xploits.bench;

import com.xploits.bench.core.Shuttle;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.function.IntUnaryOperator;

/**
 * A sparring that walks back and forth in a straight line between two blocks (crystal-aura++ R3-5, the fight
 * situations): {@link Shuttle} does the walking at {@value Circler#SPEED} blocks/s, the pauses at the ends are
 * the subclass's, and a jumping one jumps as {@link Circler} does, pauses included. It turns body and head to
 * where it walks, and to the player while it stands at an end. It holds its start until T0. Movement is
 * server-side and deterministic, like every script's.
 */
abstract class Shuttler implements Script {
    /** Blocks walked a tick: {@value Circler#SPEED} blocks/s over 20 ticks. */
    static final double SPEED_PER_TICK = Circler.SPEED / 20;

    private final Vec3i start;
    private final Vec3i end;
    private final boolean jumps;
    private final Shuttle shuttle;

    /**
     * @param start  its feet block at the end it starts from, as an offset from F
     * @param end    its feet block at the other end, the same way
     * @param pauses ticks it stands at the end each leg arrives at, by leg index
     * @param jumps  whether it jumps as {@link Circler} does
     */
    Shuttler(Vec3i start, Vec3i end, IntUnaryOperator pauses, boolean jumps) {
        this.start = start;
        this.end = end;
        this.jumps = jumps;
        this.shuttle = new Shuttle(Math.sqrt(start.getSquaredDistance(end)), SPEED_PER_TICK, pauses);
    }

    /** The start's feet block. */
    @Override
    public Vec3i anchor() {
        return start;
    }

    @Override
    public Vec3d spawnPoint(Arena arena) {
        return position(arena, -1);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        int k = tick.sinceT0();
        Vec3d at = position(tick.arena(), k);
        int direction = k < 0 ? 0 : shuttle.direction(k);
        float yaw;
        if (direction == 0) {
            yaw = Sparring.yaw(tick.player().getX() - at.x, tick.player().getZ() - at.z);
        } else {
            yaw = Sparring.yaw(direction * (end.getX() - start.getX()), direction * (end.getZ() - start.getZ()));
        }
        sparring.place(at, yaw, jumpHeight(k) == 0);
    }

    /** Where it stands {@code k} ticks after T0 (before it, at the start). */
    private Vec3d position(Arena arena, int k) {
        Vec3d a = arena.standingAt(start);
        Vec3d b = arena.standingAt(end);
        double f = shuttle.offset(k) / Math.sqrt(start.getSquaredDistance(end));
        return a.lerp(b, f).add(0, jumpHeight(k), 0);
    }

    private double jumpHeight(int k) {
        return jumps && k >= 0 ? Circler.jumpHeight(k) : 0;
    }
}
