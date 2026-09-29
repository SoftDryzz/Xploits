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

    private static final List<Case> CASES = List.of(
        new Case("open", (arena, world) -> { }),
        new Case("half wall", (arena, world) -> arena.fill(world, 2, 0, -3, 2, 0, 3, Blocks.OBSIDIAN)),
        new Case("low ceiling", (arena, world) -> arena.fill(world, -1, 2, -1, 1, 2, 1, Blocks.OBSIDIAN)),
        new Case("pillar", (arena, world) -> arena.fill(world, 2, 0, 0, 2, 2, 0, Blocks.OBSIDIAN)));

    /** Explosions as offsets from our feet: where crystals go around a sparring, above, behind and close. */
    private static final double[][] EXPLOSIONS = {
        {4, 1, 0}, {4, 1, 1}, {5, 1, -1}, {3, 1, 1}, {2.5, 0.5, 0}, {4, 2.5, 0}, {-3, 1, 0}, {3, 0.5, -2}, {1.5, 3, 1},
        {5, 0.5, 2}};

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
            }
        }
        Bench.check(partial > 0, "no case exercised a partial exposure: the probe compared only 0 and 1");
        LOG.info("[bench] exposure-cover-probe: {} comparisons in {} layouts equal vanilla, {} with a partial exposure, "
            + "{} inside-block check(s) read 1.0", compared, CASES.size(), partial, insideBlock);
        return Metrics.none();
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
