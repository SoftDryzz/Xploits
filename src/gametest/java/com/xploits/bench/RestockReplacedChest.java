package com.xploits.bench;

import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.List;

/**
 * CHECK {@code restock-replaced-chest} (ruling R27; README "A source that is no longer a container (the chest was
 * replaced) is skipped, never clicked"): two marked chests, and once restock is on the nearer one is replaced by a block
 * of stone. Restock walks to it, sees it is no longer a container, never right-clicks it, notes it unusable and, in the
 * same trip, fetches from the farther chest — one container click, one trip, the printer switched off and on once,
 * nothing lost. The replaced chest held nothing, so nothing drops when it goes.
 */
final class RestockReplacedChest implements Scenario {
    /** Centre distance from F about 5.0: chosen first. */
    static final Vec3i REPLACED = RestockStaleChest.EMPTY;
    static final Vec3i REPLACED_STAND = RestockStaleChest.EMPTY_STAND;
    /** Centre distance from F about 5.7. */
    static final Vec3i FULL = RestockStaleChest.FULL;
    static final Vec3i FULL_STAND = RestockStaleChest.FULL_STAND;
    private static final int RUN_TICKS = 640;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-replaced-chest";
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
            List.of(new RestockScene.Chest(REPLACED, List.of()),
                new RestockScene.Chest(FULL, List.of(RestockScene.stack(0, Items.STONE, 64)))),
            List.of(new RestockScene.MarkAt(REPLACED, REPLACED_STAND), new RestockScene.MarkAt(FULL, FULL_STAND)));
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        // The mark outlives its chest: a block that is not a container now stands where restock will go first.
        scene.setServerBlock(bench, REPLACED, Blocks.STONE);
        scene.run(bench, RUN_TICKS, () -> scene.trips(bench) >= 1);
        int unusable = bench.fromClient(client -> scene.restock().unusable());
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(o.trips() == 1, "trips back at the build: " + o.trips() + ", one expected");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(unusable == 1, "containers found unusable: " + unusable + ", one expected (the replaced chest)");
        Bench.check(o.interacts() == 1, "container clicks sent: " + o.interacts()
            + ", one expected (the chest still standing; never the block that replaced the other)");
        Bench.check(o.printer().equals(List.of(false, true)),
            "the print mode was switched " + o.printer() + ", off then on expected");
        Bench.check(o.player().getOrDefault("minecraft:stone", 0L) >= 10,
            "the player carries " + o.player().getOrDefault("minecraft:stone", 0L) + " stone, the wall needs ten");
        Bench.check(o.home(), "the player is not back where the trip started");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
