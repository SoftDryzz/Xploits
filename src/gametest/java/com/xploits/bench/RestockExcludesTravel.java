package com.xploits.bench;

import com.xploits.shared.core.i18n.Msg;
import com.xploits.sweep.NetherSweep;
import com.xploits.sweep.core.SweepText;
import com.xploits.travel.AutoTravel;
import com.xploits.travel.core.TravelText;
import net.minecraft.item.Items;

import java.util.List;

/**
 * CHECK {@code restock-excludes-travel} (restock spec §3 "auto-travel or nether-sweep running (they refuse while restock
 * runs)"): restock, auto-travel and nether-sweep drive the same Baritone, so while restock runs (the player carries the
 * stone, so restock only watches) auto-travel's and nether-sweep's launches refuse with their new reason — before their
 * Baritone check, which is why the bench, without Baritone, can see it. Auto-travel is set to a highway destination
 * first, so turning it on prints no coordinate. Restock itself is not disturbed.
 */
final class RestockExcludesTravel implements Scenario {
    private static final int BEFORE = 5;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-excludes-travel";
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
        scene = new RestockScene(BenchSchematic.WALL, List.of(RestockScene.stack(0, Items.STONE, 64)), List.of(), List.of());
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        AutoTravel travel = bench.meteor(AutoTravel.class);
        bench.setting(travel, "General", "destination-mode", AutoTravel.DestinationMode.HIGHWAY);
        NetherSweep sweep = bench.meteor(NetherSweep.class);
        scene.start(bench, true, false);
        scene.run(bench, BEFORE, () -> false);
        Bench.check(bench.fromClient(client -> scene.restock().isRunning()), "restock was not running before the launches");
        Msg travelSays = bench.fromClient(client -> {
            travel.enable();
            return travel.start().chat();
        });
        Msg sweepSays = bench.fromClient(client -> {
            sweep.enable();
            return sweep.start();
        });
        bench.onClient(client -> {
            travel.disable();
            sweep.disable();
        });
        Bench.check(travelSays.key() == TravelText.RESTOCK_RUNNING, "auto-travel's launch did not refuse while restock ran");
        Bench.check(sweepSays.key() == SweepText.RESTOCK_RUNNING, "nether-sweep's launch did not refuse while restock ran");
        Bench.check(bench.fromClient(client -> scene.restock().isRunning()), "the refused launches stopped restock");
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(o.trips() == 0 && o.printer().isEmpty(), "restock went on a trip while nothing was due");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
