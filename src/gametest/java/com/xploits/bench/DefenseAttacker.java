package com.xploits.bench;

import meteordevelopment.meteorclient.systems.modules.combat.Surround;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * MEASURE {@code defense-attacker} (spec {@code 2026-09-25-ingame-bench}, §Scenarios, §Metrics): how
 * much an Attacker's crystals hurt us behind Meteor's Surround. A 3 by 3 obsidian floor under F, the
 * standard loadout (AutoTotem Strict), the recorder on, Surround (reset) on at T0, for 30 s.
 *
 * <p><b>Ring guard:</b> the four Surround blocks must stand, on the server and on the client, before the
 * first attack (T0+20). Otherwise Surround's {@code protect} would break the Attacker's crystal itself
 * and the run would measure something else: ERROR.
 *
 * <p>Metrics: {@code damage_taken}, {@code self_pops} and {@code min_health}.
 */
final class DefenseAttacker implements Scenario {
    /** Surround's ring: the four blocks beside F at feet level. */
    private static final List<Vec3i> RING = List.of(new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
        new Vec3i(0, 0, 1), new Vec3i(0, 0, -1));
    /** The last tick after T0 at which the ring may be completed: the Attacker's first crystal comes at the next one. */
    static final int RING_DEADLINE = Attacker.FIRST_ATTACK - 1;

    private MeasureRun run;

    @Override
    public String name() {
        return "defense-attacker";
    }

    @Override
    public Kind kind() {
        return Kind.MEASURE;
    }

    @Override
    public int seconds() {
        return 30;
    }

    @Override
    public void arrange(Bench bench) {
        bench.arena().obsidianFloor();
        bench.meteor(Surround.class);
        run = new MeasureRun(bench, Surround.class);
        bench.arena().loadout(false);
        bench.spawn(new Attacker());
    }

    @Override
    public Metrics act(Bench bench) {
        run.start();
        boolean ring = false;
        while (!ring && bench.sinceT0() < RING_DEADLINE) {
            bench.ticks(1);
            ring = ringComplete(bench);
        }
        if (!ring) throw new BenchException("surround's ring was not complete before the first attack");
        bench.ticks(seconds() * 20 - bench.sinceT0());
        run.close();

        return new Metrics()
            .put(Metrics.DAMAGE_TAKEN, run.damageTaken())
            .put(Metrics.SELF_POPS, run.selfPops())
            .put(Metrics.MIN_HEALTH, run.minHealth());
    }

    /** All four ring blocks are obsidian on the server and on the client. */
    private static boolean ringComplete(Bench bench) {
        // Server-side arithmetic on F, never printed.
        List<BlockPos> ring = RING.stream().map(bench.arena()::at).toList();
        boolean server = bench.fromServer(srv -> ring.stream().allMatch(p -> srv.getOverworld().getBlockState(p).isOf(Blocks.OBSIDIAN)));
        boolean client = bench.fromClient(c -> c.world != null && ring.stream().allMatch(p -> c.world.getBlockState(p).isOf(Blocks.OBSIDIAN)));
        return server && client;
    }
}
