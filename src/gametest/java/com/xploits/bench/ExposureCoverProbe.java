package com.xploits.bench;

import com.xploits.pvp.crystal.ExposureAt;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.ExplosionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * CHECK {@code exposure-cover-probe} (task B2 fix round 1): crystal-aura++'s {@link ExposureAt}, at offset zero,
 * gives the exposure vanilla's own {@code ExplosionImpl.calculateReceivedDamage} gives, for explosions around
 * four cover layouts (open ground, a half wall, a low ceiling, a pillar), to within float error, and NEVER lower:
 * a lower value would let the self-budget under-estimate the damage. And a reach point whose box would sit inside
 * a block (the jump point under the low ceiling) reads 1.0 rather than the false low a ray started inside a
 * collider gives.
 *
 * <p>0.7.2 (crystal-aura++ under a roof): {@link ExposureAt#rise}, how far our box can really rise, is a full jump in the
 * open, exactly the room left under the low ceiling ({@code 2 - (double) 1.8f}: the box's height is a float), and not a
 * number in a column inside the pillar. The box raised by that rise does not overlap the ceiling, so {@link
 * ExposureAt#at} reads it for real: below 1.0 for every explosion the ceiling partly hides from us where we stand. And
 * {@link ExposureAt#stoppedByBreakable}, the half of crystal-aura++'s {@code Headroom.mayVanish} that does not come from
 * mining, reads the block that stops the rise: an obsidian ceiling is not one a crystal can break, a stone one is, and
 * stone laid over the obsidian ceiling does not count (it does not stop the rise).
 *
 * <p>Nothing is printed but counts. Explosions are offsets from our feet, computed on the client, never printed.
 */
final class ExposureCoverProbe implements Scenario {
    private static final Logger LOG = LoggerFactory.getLogger("xploits-bench");
    /** Float error allowed between the two: both are hits over total in float. */
    private static final double TOLERANCE = 1e-6;
    /** The reach's jump height ({@code MovementReach.JUMP_HEIGHT}). */
    private static final double JUMP = 1.25;
    private static final int SYNC_TICKS = 10;

    /** Blocks of one layout, as offsets from F. */
    private interface Layout {
        void build(Arena arena, ServerWorld world);
    }

    private record Case(String name, Layout layout) {
    }

    /** The low ceiling in stone, which a crystal can break (0.7.2). */
    private static final String STONE_CEILING = "stone ceiling";
    /** The obsidian low ceiling with stone over it: the stone does not stop the rise, the obsidian does (0.7.2). */
    private static final String STONE_OVER_OBSIDIAN = "stone over an obsidian ceiling";

    private static final List<Case> CASES = List.of(
        new Case("open", (arena, world) -> { }),
        new Case("half wall", (arena, world) -> arena.fill(world, 2, 0, -3, 2, 0, 3, Blocks.OBSIDIAN)),
        new Case("low ceiling", (arena, world) -> arena.fill(world, -1, 2, -1, 1, 2, 1, Blocks.OBSIDIAN)),
        new Case("pillar", (arena, world) -> arena.fill(world, 2, 0, 0, 2, 2, 0, Blocks.OBSIDIAN)),
        new Case(STONE_CEILING, (arena, world) -> arena.fill(world, -1, 2, -1, 1, 2, 1, Blocks.STONE)),
        new Case(STONE_OVER_OBSIDIAN, (arena, world) -> {
            arena.fill(world, -1, 2, -1, 1, 2, 1, Blocks.OBSIDIAN);
            arena.fill(world, -1, 3, -1, 1, 3, 1, Blocks.STONE);
        }));

    /**
     * Explosions as offsets from our feet: where crystals go around a sparring, above, behind and close. The last one
     * (0.7.2) sits above the low ceiling's edge, 3 up and 3 out: the ceiling hides our upper half from it, not our lower.
     */
    private static final double[][] EXPLOSIONS = {
        {4, 1, 0}, {4, 1, 1}, {5, 1, -1}, {3, 1, 1}, {2.5, 0.5, 0}, {4, 2.5, 0}, {-3, 1, 0}, {3, 0.5, -2}, {1.5, 3, 1},
        {5, 0.5, 2}, {3, 3, 0}};
    /** The room over a standing box under the low ceiling, whose underside is 2 above our feet: the box is 1.8f tall. */
    private static final double ROOM_UNDER_THE_CEILING = 2 - (double) 1.8f;
    /** How close the measured rise must be to it. */
    private static final double RISE_TOLERANCE = 1e-9;
    /** The pillar's column, as an offset from our feet: a box moved there is inside the pillar. */
    private static final int PILLAR_X = 2;

    @Override
    public String name() {
        return "exposure-cover-probe";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 12;
    }

    @Override
    public void arrange(Bench bench) {
    }

    @Override
    public Metrics act(Bench bench) {
        int compared = 0;
        int partial = 0;
        int insideBlock = 0;
        int lowered = 0;
        int roofs = 0;
        for (Case c : CASES) {
            bench.onServer(srv -> {
                ServerWorld world = srv.getOverworld();
                bench.arena().fill(world, -6, 0, -6, 6, 4, 6, Blocks.AIR);
                c.layout().build(bench.arena(), world);
            });
            bench.ticks(SYNC_TICKS);
            List<double[]> found = bench.fromClient(client -> compare(client));
            for (double[] r : found) {
                double vanilla = r[0];
                double ours = r[1];
                Bench.check(ours >= vanilla - TOLERANCE, c.name() + ": ours " + ours + " is LOWER than vanilla's " + vanilla);
                Bench.check(Math.abs(ours - vanilla) <= TOLERANCE, c.name() + ": ours " + ours + " differs from vanilla's " + vanilla);
                compared++;
                if (vanilla > 0 && vanilla < 1) partial++;
            }
            if (c.name().equals("low ceiling")) {
                boolean allFull = bench.fromClient(client -> {
                    PlayerEntity p = client.player;
                    for (double[] e : EXPLOSIONS) {
                        Vec3d explosion = p.getEntityPos().add(e[0], e[1], e[2]);
                        if (ExposureAt.at(p, explosion, 0, JUMP, 0, new ExposureAt.Budget(10_000)) != 1.0) return false;
                    }
                    return true;
                });
                Bench.check(allFull, "the jump point inside the low ceiling did not read 1.0");
                insideBlock++;
                // 0.7.2: the rise a jump really makes under it, and what is read there.
                double[] under = bench.fromClient(ExposureCoverProbe::underTheCeiling);
                Bench.check(Math.abs(under[0] - ROOM_UNDER_THE_CEILING) <= RISE_TOLERANCE,
                    "the rise under the low ceiling is " + under[0] + ", not the " + ROOM_UNDER_THE_CEILING + " left over the box");
                Bench.check(under[1] == 0, "the box raised to the low ceiling overlaps it");
                Bench.check(under[2] > 0, "no explosion is partly hidden by the low ceiling where we stand");
                Bench.check(under[3] == under[2], (int) (under[2] - under[3]) + " of " + (int) under[2]
                    + " partly hidden explosion(s) read 1.0 at the rise under the low ceiling");
                lowered = (int) under[2];
            }
            // 0.7.2, fix round 1: whether the block that stops the rise is one a crystal can break (the adapter's
            // measurement behind Headroom.mayVanish): obsidian no, stone yes, stone over obsidian no.
            if (c.name().equals("low ceiling") || c.name().equals(STONE_CEILING) || c.name().equals(STONE_OVER_OBSIDIAN)) {
                double[] stop = bench.fromClient(ExposureCoverProbe::stop);
                Bench.check(Math.abs(stop[0] - ROOM_UNDER_THE_CEILING) <= RISE_TOLERANCE,
                    c.name() + ": the rise is " + stop[0] + ", not the " + ROOM_UNDER_THE_CEILING + " left over the box");
                boolean breakable = c.name().equals(STONE_CEILING);
                Bench.check((stop[1] == 1) == breakable, c.name() + ": the block that stops the rise reads as "
                    + (stop[1] == 1 ? "" : "not ") + "breakable by a crystal");
                roofs++;
            }
            if (c.name().equals("open")) {
                double rise = bench.fromClient(client -> ExposureAt.rise(client.player, 0, 0, JUMP));
                Bench.check(rise == JUMP, "the rise in the open is " + rise + ", not a full jump");
            }
            if (c.name().equals("pillar")) {
                double rise = bench.fromClient(client -> ExposureAt.rise(client.player, PILLAR_X, 0, JUMP));
                Bench.check(Double.isNaN(rise), "a column inside the pillar has a rise of " + rise + ", not none");
            }
        }
        Bench.check(partial > 0, "no case exercised a partial exposure: the probe compared only 0 and 1");
        LOG.info("[bench] exposure-cover-probe: {} comparisons in {} layouts equal vanilla, {} with a partial exposure, "
            + "{} inside-block check(s) read 1.0, {} partly hidden explosion(s) read below 1.0 at the rise under the low"
            + " ceiling, {} ceiling(s) read right as breakable or not", compared, CASES.size(), partial, insideBlock, lowered,
            roofs);
        return Metrics.none();
    }

    /**
     * On the client thread: the rise at our own column, and whether the block that stops it is one a crystal can break
     * (1) or not (0), as crystal-aura++'s headroom reads it ({@link ExposureAt#stoppedByBreakable}, only for a rise a
     * block really stops).
     */
    private static double[] stop(MinecraftClient client) {
        PlayerEntity p = client.player;
        double rise = ExposureAt.rise(p, 0, 0, JUMP);
        boolean breakable = rise >= 0 && rise < JUMP && ExposureAt.stoppedByBreakable(p, 0, 0, rise);
        return new double[] {rise, breakable ? 1 : 0};
    }

    /**
     * Under the low ceiling, on the client thread: the rise at our own column, whether our box raised by it overlaps a
     * block (1) or not (0), how many explosions the ceiling partly hides from us where we stand (vanilla's exposure
     * strictly between 0 and 1), and how many of those {@link ExposureAt#at} reads below 1.0 at that rise.
     */
    private static double[] underTheCeiling(MinecraftClient client) {
        PlayerEntity p = client.player;
        double rise = ExposureAt.rise(p, 0, 0, JUMP);
        if (!Double.isFinite(rise)) return new double[] {rise, 1, 0, 0};
        boolean overlaps = p.getEntityWorld().getBlockCollisions(p, p.getBoundingBox().offset(0, rise, 0)).iterator().hasNext();
        int partial = 0;
        int below = 0;
        for (double[] e : EXPLOSIONS) {
            Vec3d explosion = p.getEntityPos().add(e[0], e[1], e[2]);
            double standing = ExplosionImpl.calculateReceivedDamage(explosion, p);
            if (!(standing > 0 && standing < 1)) continue;
            partial++;
            if (ExposureAt.at(p, explosion, 0, rise, 0, new ExposureAt.Budget(10_000)) < 1.0) below++;
        }
        return new double[] {rise, overlaps ? 1 : 0, partial, below};
    }

    /** (vanilla, ours) exposures for every explosion, on the client thread. */
    private static List<double[]> compare(MinecraftClient client) {
        PlayerEntity p = client.player;
        List<double[]> out = new ArrayList<>();
        for (double[] e : EXPLOSIONS) {
            Vec3d explosion = p.getEntityPos().add(e[0], e[1], e[2]);
            double vanilla = ExplosionImpl.calculateReceivedDamage(explosion, p);
            double ours = ExposureAt.at(p, explosion, 0, 0, 0, new ExposureAt.Budget(10_000));
            out.add(new double[] {vanilla, ours});
        }
        return out;
    }
}
