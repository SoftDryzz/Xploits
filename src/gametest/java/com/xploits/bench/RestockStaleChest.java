package com.xploits.bench;

import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * CHECK {@code restock-stale-chest} (restock spec §3 "a stale source is noted and the next nearest is tried"): two marked
 * chests, neither opened before, so both are unknown. The nearer one is empty: restock opens it, finds no stone, closes
 * it and, in the same trip, walks to the farther one, which has stone, takes it and comes back — two container clicks,
 * one trip, the printer switched off and on once.
 */
final class RestockStaleChest implements Scenario {
    /** Centre distance from F about 5.0: chosen first. */
    static final Vec3i EMPTY = new Vec3i(-3, 0, 4);
    static final Vec3i EMPTY_STAND = new Vec3i(-3, 0, 3);
    /** Centre distance from F about 5.7. */
    static final Vec3i FULL = new Vec3i(4, 0, 4);
    static final Vec3i FULL_STAND = new Vec3i(4, 0, 3);
    /** 25 s at 20 tps, inside the 35 s budget with the arrangement and the final check: a failure ends in its message, not a timeout. */
    private static final int RUN_TICKS = 500;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-stale-chest";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 35;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL, List.of(),
            List.of(new RestockScene.Chest(EMPTY, List.of()),
                new RestockScene.Chest(FULL, List.of(RestockScene.stack(0, Items.STONE, 64)))),
            List.of(new RestockScene.MarkAt(EMPTY, EMPTY_STAND), new RestockScene.MarkAt(FULL, FULL_STAND)));
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, RUN_TICKS, () -> scene.trips(bench) >= 1);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(o.trips() == 1, "trips back at the build: " + o.trips() + ", one expected");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.interacts() == 2, "container clicks sent: " + o.interacts() + ", two expected (the empty chest, then the full one)");
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off then on expected");
        Bench.check(o.player().getOrDefault("minecraft:stone", 0L) >= 10,
            "the player carries " + o.player().getOrDefault("minecraft:stone", 0L) + " stone, the wall needs ten");
        Bench.check(o.home(), "the player is not back where the trip started");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
