package com.xploits.bench;

import com.xploits.restock.Restock;
import com.xploits.restock.core.RestockReason;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Optional;

/**
 * CHECK {@code restock-no-litematica} (restock spec §3 "Without Litematica → refuse", §6): the default bench runs without
 * Litematica, so Xploits must load, and restock, turned on without its bench seams, must refuse at once naming Litematica
 * and stay off. No T0 (ruling P1): nothing is recorded, so the close is not called either.
 */
final class RestockNoLitematica implements Scenario {
    @Override
    public String name() {
        return "restock-no-litematica";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 5;
    }

    @Override
    public void arrange(Bench bench) {
        Bench.check(!bench.fromClient(client -> FabricLoader.getInstance().isModLoaded("litematica")),
            "the default bench must run without Litematica");
    }

    @Override
    public Metrics act(Bench bench) {
        Restock restock = bench.fromClient(client -> Modules.get().get(Restock.class));
        bench.onClient(client -> {
            restock.useForBench(null, null, null);
            restock.enable();
        });
        bench.ticks(2);
        Bench.check(!bench.fromClient(client -> restock.isActive()), "restock stayed on without Litematica");
        Bench.check(bench.fromClient(client -> restock.lastReason()).equals(Optional.of(RestockReason.NO_LITEMATICA)),
            "restock did not say it needs Litematica");
        return Metrics.none();
    }
}
