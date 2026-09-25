package com.xploits.bench;

import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModules;

/**
 * CHECK {@code autopvp-engages} (spec {@code 2026-09-25-ingame-bench}, §Scenarios): auto-pvp, turned on
 * with a player in reach, targets it and turns crystal-aura on; turned off, it turns crystal-aura off.
 *
 * <p>Standard loadout, recorder off, crystal-aura off at T0, profile {@code balanced}, a Still sparring.
 * Auto-pvp runs 10 s, then it is turned off and one tick passes.
 */
final class AutoPvpEngages implements Scenario {
    private static final int RUN_TICKS = 200;

    private AutoPvpScene scene;

    @Override
    public String name() {
        return "autopvp-engages";
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
        scene = AutoPvpScene.arrange(bench, "balanced");
        bench.arena().loadout(false);
        bench.spawn(new Still());
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, false);
        int engaged = 0;
        int targeted = 0;
        int planned = 0;
        int auraOn = 0;
        int all = 0;
        for (int i = 0; i < RUN_TICKS; i++) {
            bench.ticks(1);
            AutoPvpScene.Look look = scene.look(bench);
            boolean inFight = look.state() != null && look.state() != CombatState.NO_COMBAT;
            boolean onSparring = Sparring.NAME.equals(look.target());
            if (inFight) engaged++;
            if (onSparring) targeted++;
            if (look.planEnablesAura()) planned++;
            if (look.auraOn()) auraOn++;
            if (inFight && onSparring && look.planEnablesAura() && look.auraOn()) all++;
        }
        Bench.check(all > 0, "no tick had auto-pvp engaged on the sparring with crystal-aura planned and on (ticks: engaged "
            + engaged + ", targeted " + targeted + ", planned " + planned + ", crystal-aura on " + auraOn + ")");

        bench.onClient(client -> scene.autoPvp.disable());
        bench.ticks(1);
        Bench.check(!bench.fromClient(client -> AutoPvpScene.on(ManagedModules.CRYSTAL_AURA)),
            "crystal-aura is still on after auto-pvp was turned off");
        bench.finish();
        return Metrics.none();
    }
}
