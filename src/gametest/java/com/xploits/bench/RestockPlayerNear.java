package com.xploits.bench;

import com.xploits.restock.core.RestockReason;
import com.xploits.restock.core.RestockTrip;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Optional;

/**
 * CHECK {@code restock-player-near} (restock spec §3 Guards, "a non-friend player within player-distance"): while restock
 * walks to a marked chest, a player who is not a Meteor friend appears five blocks away. Restock stops at once, before
 * any container click: Baritone's goal is cancelled, the printer stays switched off (restock says so), the printer
 * marker is gone (a stop is not a crash), and nothing is lost.
 */
final class RestockPlayerNear implements Scenario {
    static final Vec3i CHEST = new Vec3i(0, 0, 9);
    static final Vec3i STAND = new Vec3i(0, 0, 8);
    private static final int TO_TRAVEL_TICKS = 200;
    private static final int STOP_TICKS = 40;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-player-near";
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
            List.of(new RestockScene.Chest(CHEST, List.of(RestockScene.stack(0, Items.STONE, 64)))),
            List.of(new RestockScene.MarkAt(CHEST, STAND)));
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, TO_TRAVEL_TICKS, () -> scene.phase(bench).equals(Optional.of(RestockTrip.Phase.TRAVEL)));
        Bench.check(scene.phase(bench).equals(Optional.of(RestockTrip.Phase.TRAVEL)), "restock never started walking");
        bench.spawn(new Still());
        scene.run(bench, STOP_TICKS, () -> false);
        boolean idle = bench.fromClient(client -> scene.mover().idle());
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(!o.on() && o.reason().equals(Optional.of(RestockReason.PLAYER_NEAR)),
            "with a stranger five blocks away restock ended with " + RestockScene.words(o.reason()) + ", PLAYER_NEAR expected");
        Bench.check(o.interacts() == 0, "container clicks sent: " + o.interacts() + ", none expected");
        Bench.check(o.printer().equals(List.of(false)),
            "the print mode was switched " + o.printer() + ", off and left off expected");
        Bench.check(idle, "the walker still had a goal after the stop");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
