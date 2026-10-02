package com.xploits.bench;

import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockTrip;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * CHECK {@code restock-moved} (restock spec §3 "the player's movement keys during a trip (cancel the trip, printer stays
 * paused, say so)"): the player presses forward while restock walks to a marked chest. Restock stops — the player wins —
 * with the printer left off, the walker's goal cancelled and no container click.
 */
final class RestockMoved implements Scenario {
    private static final int TO_TRAVEL_TICKS = 200;
    private static final int STOP_TICKS = 20;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-moved";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 15;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.ROW, List.of(),
            List.of(new RestockScene.Chest(RestockPlayerNear.CHEST, List.of(RestockScene.stack(0, Items.STONE, 64)))),
            List.of(new RestockScene.MarkAt(RestockPlayerNear.CHEST, RestockPlayerNear.STAND)));
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, TO_TRAVEL_TICKS, () -> scene.phase(bench).equals(Optional.of(RestockTrip.Phase.TRAVEL)));
        Bench.check(scene.phase(bench).equals(Optional.of(RestockTrip.Phase.TRAVEL)), "restock never started walking");
        bench.holdKey(options -> options.forwardKey, true);
        scene.run(bench, STOP_TICKS, () -> false);
        bench.holdKey(options -> options.forwardKey, false);
        boolean idle = bench.fromClient(client -> scene.mover().idle());
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(!o.on() && o.reason().equals(Optional.of(RestockReason.PLAYER_MOVED)),
            "with forward held restock ended with " + RestockScene.words(o.reason()) + ", PLAYER_MOVED expected");
        Bench.check(o.interacts() == 0, "container clicks sent: " + o.interacts() + ", none expected");
        Bench.check(o.printer().equals(List.of(false)),
            "the print mode was switched " + o.printer() + ", off and left off expected");
        Bench.check(idle, "the walker still had a goal after the stop");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
