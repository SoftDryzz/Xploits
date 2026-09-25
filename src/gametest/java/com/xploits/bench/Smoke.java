package com.xploits.bench;

import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightRecord;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;

import java.util.List;

/**
 * Temporary CHECK (until the real scenarios land): the world loads, the player is named, a Meteor
 * module is reset and set, T0 turns the recorder and a combat module on, and the close reads no fight
 * in a quiet world. {@link BenchTest} then checks that the teardown turned everything off.
 */
final class Smoke implements Scenario {
    private AutoTotem totem;

    @Override
    public String name() {
        return "smoke";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 1;
    }

    @Override
    public void arrange(Bench bench) {
        totem = bench.meteor(AutoTotem.class);
        bench.setting(totem, "General", "mode", AutoTotem.Mode.Strict);
    }

    @Override
    public Metrics act(Bench bench) {
        Bench.check(bench.fromClient(client -> client.world != null && client.player != null), "the world did not load");
        String name = bench.player();
        Bench.check(name.equals(bench.fromClient(client -> client.player.getGameProfile().name())),
            "the server and the client disagree on the player's name");

        bench.start(true, AutoTotem.class);
        bench.ticks(seconds() * 20);
        Bench.check(bench.fromClient(client -> Modules.get().get(FightRecorder.class).isActive()),
            "the recorder is not on after T0");
        Bench.check(bench.fromClient(client -> totem.isActive() && totem.settings.get("mode").get() == AutoTotem.Mode.Strict),
            "auto-totem is not on in strict mode after T0");

        List<FightRecord> fights = bench.finish();
        Bench.check(fights.isEmpty(), "a quiet world recorded a fight");
        return Metrics.none();
    }
}
