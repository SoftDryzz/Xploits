package com.xploits.bench;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * Runs a circle of radius 1.5 around the centre of a 5 by 5 pad at F+(6,0,0), starting at the point
 * nearest the player, counter-clockwise seen from above, at 4.3 blocks/s; it jumps every 20 ticks from
 * tick 10 (a parabola 1.25 blocks high over 12 ticks), with {@code onGround} set to match and the head
 * turned to the heading. It holds its start point until T0.
 */
public final class Circler implements Script {
    private static final Vec3i ANCHOR = new Vec3i(6, 0, 0);
    static final double RADIUS = 1.5;
    /** Blocks per second along the circle. */
    static final double SPEED = 4.3;
    /** Radians per tick (4.3 / 1.5 rad/s over 20 ticks). */
    private static final double RADIANS_PER_TICK = SPEED / RADIUS / 20;
    static final int FIRST_JUMP = 10;
    static final int JUMP_EVERY = 20;
    static final int JUMP_TICKS = 12;
    static final double JUMP_HEIGHT = 1.25;

    @Override
    public String name() {
        return "circler";
    }

    /** The pad's centre block; the circle runs around its bottom centre. */
    @Override
    public Vec3i anchor() {
        return ANCHOR;
    }

    /** The circle's point nearest the player. */
    @Override
    public Vec3d spawnPoint(Arena arena) {
        return position(arena, 0);
    }

    @Override
    public void build(Arena arena, ServerWorld world) {
        arena.pad(world, ANCHOR, 2);
    }

    @Override
    public void tick(Sparring sparring, Tick tick) {
        int k = Math.max(0, tick.sinceT0());
        double a = angle(k);
        float heading = Sparring.yaw(-Math.sin(a), -Math.cos(a));
        sparring.place(position(tick.arena(), k), heading, jumpHeight(k) == 0);
    }

    /**
     * {@code p(a) = centre + r (cos a, -sin a)} in (x, z): a growing turns +x toward -z (north), which is
     * counter-clockwise seen from above; {@code a = pi} is the point on the player's side.
     */
    private static double angle(int k) {
        return Math.PI + RADIANS_PER_TICK * k;
    }

    private static Vec3d position(Arena arena, int k) {
        Vec3d centre = arena.standingAt(ANCHOR);
        double a = angle(k);
        return new Vec3d(centre.x + RADIUS * Math.cos(a), centre.y + jumpHeight(k), centre.z - RADIUS * Math.sin(a));
    }

    /** Height above the pad at tick {@code k}: a parabola peaking at 1.25 in the middle of each 12-tick jump. */
    static double jumpHeight(int k) {
        if (k < FIRST_JUMP) return 0;
        int t = (k - FIRST_JUMP) % JUMP_EVERY;
        if (t >= JUMP_TICKS) return 0;
        double f = (double) t / JUMP_TICKS;
        return 4 * JUMP_HEIGHT * f * (1 - f);
    }
}
