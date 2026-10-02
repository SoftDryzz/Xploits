package com.xploits.bench;

import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * CHECK {@code restock-trip} (restock spec §6): a stone wall to build, no stone carried, a marked chest six blocks away
 * with two stacks. Restock switches the (fake) print mode off, walks to the spot the chest was marked from, looks at it
 * and clicks it once, takes a stack, closes it, walks back and switches the print mode on again — with every packet
 * keeping the rules, no container click while walking, the server's own re-check passing the click, nothing lost from
 * the player and the chest, nothing on the ground, and the client and the server agreeing at the end.
 */
final class RestockTrips implements Scenario {
    static final Vec3i CHEST = new Vec3i(0, 0, 6);
    static final Vec3i STAND = new Vec3i(0, 0, 5);
    private static final int RUN_TICKS = 500;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-trip";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 30;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL, List.of(),
            List.of(new RestockScene.Chest(CHEST, List.of(RestockScene.stack(0, Items.STONE, 64),
                RestockScene.stack(1, Items.STONE, 64)))),
            List.of(new RestockScene.MarkAt(CHEST, STAND)));
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, RUN_TICKS, () -> scene.trips(bench) >= 1);
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(o.trips() == 1, "trips back at the build: " + o.trips() + ", one expected");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off then on expected");
        Bench.check(o.player().getOrDefault("minecraft:stone", 0L) >= 10,
            "the player carries " + o.player().getOrDefault("minecraft:stone", 0L) + " stone, the wall needs ten");
        Bench.check(o.home(), "the player is not back where the trip started");
        Bench.check(o.interacts() == 1, "container clicks sent: " + o.interacts() + ", one expected");
        Bench.check(o.closes() == 1, "screens closed: " + o.closes() + ", one expected");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
