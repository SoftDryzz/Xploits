package com.xploits.bench;

import com.xploits.bench.core.EscapePace;
import net.minecraft.util.math.Vec3d;

/**
 * Task A2+ requirement 6: at {@value EscapePace#ESCAPE_TOTEMS} totems left or fewer ({@link Sparring#totemsLeft}),
 * the opponent moves {@value EscapePace#ESCAPE_DISTANCE} blocks away over {@value EscapePace#ESCAPE_TICKS}
 * ticks, as a thrown pearl would: a fixed direction picked once at the trigger tick (straight away from the
 * player, at that instant), a steady ramp along it ({@link EscapePace#displacement}), then it holds there —
 * having put the full distance between itself and where it stood, it keeps it, rather than closing back in.
 * Horizontal only: the escape never changes height.
 */
public final class Escape implements FightBehaviour {
    private boolean triggered;
    private int triggerTick;
    private Vec3d start;
    private Vec3d direction;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        if (!triggered) {
            if (!EscapePace.triggered(sparring.totemsLeft())) return;
            triggered = true;
            triggerTick = tick.sinceT0();
            start = sparring.getEntityPos();
            direction = awayFrom(tick.player().getEntityPos(), start);
        }
        double distance = EscapePace.displacement(tick.sinceT0() - triggerTick);
        Vec3d at = start.add(direction.multiply(distance));
        sparring.face(tick.player());
        sparring.place(at, Sparring.yaw(tick.player().getX() - at.x, tick.player().getZ() - at.z), true);
    }

    /** The horizontal unit vector from {@code target} to {@code from}; an arbitrary fixed one if they coincide. */
    private static Vec3d awayFrom(Vec3d target, Vec3d from) {
        Vec3d flat = new Vec3d(from.x - target.x, 0, from.z - target.z);
        return flat.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : flat.normalize();
    }

    /** Whether the escape has triggered. Server thread. */
    public boolean triggered() {
        return triggered;
    }
}
