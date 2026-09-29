package com.xploits.bench;

import com.xploits.bench.core.EscapePace;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

/**
 * Task A2+ requirement 6: at {@value EscapePace#ESCAPE_TOTEMS} totems left or fewer ({@link Sparring#totemsLeft}),
 * the opponent moves {@value EscapePace#ESCAPE_DISTANCE} blocks away over {@value EscapePace#ESCAPE_TICKS}
 * ticks, as a thrown pearl would: a fixed direction picked once at the trigger tick (straight away from the
 * player, at that instant), a steady ramp along it ({@link EscapePace#displacement}), then it holds there —
 * having put the full distance between itself and where it stood, it keeps it, rather than closing back in.
 * Horizontal only: the escape never changes height.
 *
 * <p>Fix round 1 (review-a2plus.md, Critical): {@value EscapePace#ESCAPE_DISTANCE} is larger than
 * {@link Arena#FLOOR_RADIUS}, so left unchecked every trigger would send the opponent past the cleared floor,
 * with no guarantee of solid, open ground out there — {@link Sparring#place} is a raw teleport with no bounds
 * or collision check of its own. Two things now guard every tick's destination before it is used: the ideal
 * displacement is clamped to stay inside the cleared floor ({@link EscapePace#maxDisplacement}, a margin of
 * {@value #ARENA_MARGIN} block in from the edge), and, within that clamp, the actual landing spot is the
 * nearest free one — two air blocks, solid ground under them — working backward one block at a time from the
 * clamped ideal toward the trigger point itself ({@link EscapePace#landingDisplacement}), which is always
 * free (the opponent already stood there) and so is always where the search bottoms out if every other
 * candidate is blocked.
 */
public final class Escape implements FightBehaviour {
    /** How far in from the cleared floor's own edge the escape stays (review-a2plus.md's own suggestion). */
    private static final int ARENA_MARGIN = 1;

    private boolean triggered;
    private int triggerTick;
    private Vec3d start;
    private Vec3d direction;
    /** F's own world position (bottom-centre), captured at the trigger tick: the arena-bound clamp's centre. */
    private Vec3d arenaCentre;

    @Override
    public void tick(Sparring sparring, Script.Tick tick) {
        if (!triggered) {
            if (!EscapePace.triggered(sparring.totemsLeft())) return;
            triggered = true;
            triggerTick = tick.sinceT0();
            start = sparring.getEntityPos();
            direction = awayFrom(tick.player().getEntityPos(), start);
            arenaCentre = tick.arena().standingAt(Vec3i.ZERO);
        }
        double ideal = EscapePace.displacement(tick.sinceT0() - triggerTick);
        double halfExtent = Arena.FLOOR_RADIUS - ARENA_MARGIN;
        double bound = EscapePace.maxDisplacement(start.x - arenaCentre.x, start.z - arenaCentre.z,
            direction.x, direction.z, halfExtent);
        double clamped = Math.min(ideal, bound);
        ServerWorld world = tick.world();
        double distance = EscapePace.landingDisplacement(clamped, t -> isFreeLanding(world, start.add(direction.multiply(t))));
        Vec3d at = start.add(direction.multiply(distance));
        sparring.face(tick.player());
        sparring.place(at, Sparring.yaw(tick.player().getX() - at.x, tick.player().getZ() - at.z), true);
    }

    /** Two free (air) blocks at {@code pos} and no entity check needed (the caller only tries points along its
     * own escape line), with solid ground under them — never a place to teleport into a block. */
    private static boolean isFreeLanding(ServerWorld world, Vec3d pos) {
        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        return world.getBlockState(feet).isAir() && world.getBlockState(feet.up()).isAir()
            && !world.getBlockState(feet.down()).isAir();
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
