package com.xploits.bench;

import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModules;

/**
 * CHECK {@code profile-defensive} (spec {@code 2026-09-25-ingame-bench}, §Scenarios): with the
 * {@code defensive} profile, a surrounded target puts auto-pvp in SURROUNDED, where it wants auto-city,
 * yet auto-city and auto-anvil never turn on, the panel lists auto-city as off by profile, and the
 * profile never makes the phase OUT_OF_RESOURCES.
 *
 * <p>Standard loadout plus a pickaxe (so auto-city is not short of one), recorder off, a Defender at
 * F+(4,0,0): closer than the MEASURE rule allows, on purpose, since SURROUNDED needs the target within 5.
 * Auto-pvp runs 10 s.
 */
final class ProfileDefensive implements Scenario {
    private static final int RUN_TICKS = 200;
    private static final int DEFENDER_DISTANCE = 4;

    private AutoPvpScene scene;

    @Override
    public String name() {
        return "profile-defensive";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 11;
    }

    @Override
    public void arrange(Bench bench) {
        scene = AutoPvpScene.arrange(bench, "defensive");
        bench.arena().loadout(true);
        bench.spawn(new Defender(DEFENDER_DISTANCE));
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, false);
        int surrounded = 0;
        int outOfResources = 0;
        int cityOrAnvilOn = 0;
        int cityOffByProfile = 0;
        int firstOutOfResources = -1;
        int firstCityOrAnvilOn = -1;
        for (int i = 1; i <= RUN_TICKS; i++) {
            bench.ticks(1);
            AutoPvpScene.Look look = scene.look(bench);
            if (look.state() == CombatState.SURROUNDED) surrounded++;
            if (look.state() == CombatState.OUT_OF_RESOURCES) {
                outOfResources++;
                if (firstOutOfResources < 0) firstOutOfResources = i;
            }
            if (look.cityOn() || look.anvilOn()) {
                cityOrAnvilOn++;
                if (firstCityOrAnvilOn < 0) firstCityOrAnvilOn = i;
            }
            if (look.profileOff().contains(ManagedModules.AUTO_CITY.name())) cityOffByProfile++;
        }
        Bench.check(outOfResources == 0, "auto-pvp reported OUT_OF_RESOURCES on " + outOfResources
            + " ticks (first at tick " + firstOutOfResources + ")");
        Bench.check(cityOrAnvilOn == 0, "auto-city or auto-anvil was on for " + cityOrAnvilOn
            + " ticks (first at tick " + firstCityOrAnvilOn + ")");
        Bench.check(surrounded > 0, "auto-pvp never reached SURROUNDED");
        Bench.check(cityOffByProfile > 0, "the panel never listed auto-city as off by profile");
        bench.finish();
        return Metrics.none();
    }
}
