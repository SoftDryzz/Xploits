package com.xploits.bench;

import java.util.List;
import java.util.Set;

/**
 * CHECK {@code restock-nowhere} (restock spec §3 "Material nowhere (no source within reach has it): no trip; say which
 * material and how many are missing; the printer keeps printing everything else"): a wall to build, no stone carried,
 * no mark and no stash index. Restock names stone as nowhere, never leaves, never touches the print mode and stays on.
 */
final class RestockNowhere implements Scenario {
    private static final int RUN_TICKS = 160;

    private RestockScene scene;

    @Override
    public String name() {
        return "restock-nowhere";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 10;
    }

    @Override
    public void arrange(Bench bench) {
        scene = new RestockScene(BenchSchematic.WALL, List.of(), List.of(), List.of());
        scene.arrange(bench);
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, true, false);
        scene.run(bench, RUN_TICKS, () -> !bench.fromClient(client -> scene.restock().nowhere()).isEmpty());
        Set<String> nowhere = bench.fromClient(client -> scene.restock().nowhere());
        RestockScene.Outcome o = scene.finish(bench);
        Bench.check(nowhere.equals(Set.of("minecraft:stone")), "materials said to be nowhere: " + nowhere + ", stone expected");
        Bench.check(o.on() && o.reason().isEmpty(), "restock ended with " + RestockScene.words(o.reason()));
        Bench.check(o.trips() == 0 && o.interacts() == 0, "restock went on a trip with nowhere to go");
        Bench.check(o.printer().isEmpty(), "the print mode was switched " + o.printer() + ", untouched expected");
        RestockScene.checkClean(o);
        return Metrics.none();
    }
}
