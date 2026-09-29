package com.xploits.pvp.crystal;

import com.xploits.pvp.crystal.core.ExposureGrid;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Vanilla's explosion exposure ({@code ExplosionImpl.calculateReceivedDamage}, yarn 1.21.11, read from the jar)
 * for an entity's box moved to a point it could reach: the fraction of {@link ExposureGrid}'s sample points
 * whose {@code COLLIDER}/{@code NONE} ray to the explosion, cast in the entity's own world with the entity as
 * the shape context, does not hit anything. At offset zero it is the very value vanilla's method returns (same
 * grid, same rays); the entity itself stays where it is, only the sample points move.
 *
 * <p>Cost: one raycast per sample point (45 for a player). {@link #at} spends from a caller-owned budget of
 * raycasts and, when the budget cannot pay for the whole grid, measures nothing and returns 1.0, the exposure
 * that can only overstate the damage: a capped tick is more cautious, never less.
 */
public final class ExposureAt {
    private ExposureAt() {
    }

    /** The raycasts still affordable; {@link #at} lowers it by what it casts. */
    public static final class Budget {
        private int left;

        public Budget(int left) {
            this.left = left;
        }

        public void reset(int left) {
            this.left = left;
        }

        public int left() {
            return left;
        }
    }

    /**
     * The exposure of {@code entity} moved by {@code (dx, dy, dz)} to an explosion at {@code explosion}, or 1.0
     * when the box gives no sample points, the moved box overlaps a block, or the budget cannot pay for them all.
     */
    public static double at(Entity entity, Vec3d explosion, double dx, double dy, double dz, Budget budget) {
        Box box = entity.getBoundingBox();
        // A reach point whose box overlaps a block is not a place we can stand (the surface we would really
        // stand on is closer to the explosion and exposed): every ray from inside a collider hits at once and
        // would read a false low. Read it at 1.0, and spend no raycast on it.
        Box moved = box.offset(dx, dy, dz);
        if (entity.getEntityWorld().getBlockCollisions(entity, moved).iterator().hasNext()) return 1.0;
        double[] samples = ExposureGrid.samples(moved.minX, moved.minY, moved.minZ, moved.maxX, moved.maxY, moved.maxZ);
        int total = samples.length / 3;
        if (total == 0 || budget.left < total) return 1.0;
        budget.left -= total;
        int hits = 0;
        for (int i = 0; i < samples.length; i += 3) {
            Vec3d from = new Vec3d(samples[i], samples[i + 1], samples[i + 2]);
            var result = entity.getEntityWorld().raycast(new RaycastContext(from, explosion,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity));
            if (result.getType() == HitResult.Type.MISS) hits++;
        }
        return (float) hits / (float) total;
    }
}
