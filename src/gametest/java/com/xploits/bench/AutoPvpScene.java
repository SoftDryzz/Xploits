package com.xploits.bench;

import com.xploits.pvp.AutoPvp;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.Plan;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;

import java.util.List;
import java.util.Optional;

/**
 * What the auto-pvp CHECKs share (spec {@code 2026-09-25-ingame-bench}, §Arena and loadouts): every
 * Meteor module auto-pvp may turn on is reset, CrystalAura gets {@code pause-on-lag} off, auto-pvp is reset and given a
 * profile, and CrystalAura is off before T0. Then, once per tick, what auto-pvp and its modules show.
 */
final class AutoPvpScene {
    final AutoPvp autoPvp;

    private AutoPvpScene(AutoPvp autoPvp) {
        this.autoPvp = autoPvp;
    }

    /** The resets and the profile; call it first in {@code arrange}, before the loadout. */
    static AutoPvpScene arrange(Bench bench, String profile) {
        CrystalAura aura = bench.meteor(CrystalAura.class);
        bench.setting(aura, "Pause", "pause-on-lag", false);
        bench.onClient(client -> {
            for (ManagedModule managed : ManagedModules.ALL) {
                if (managed.equals(ManagedModules.CRYSTAL_AURA)) continue;
                // Looked up by name, as auto-pvp does; one Meteor does not register (anti-anchor in
                // 1.21.11) is one auto-pvp can never turn on, so there is nothing to reset.
                Module module = Modules.get().get(managed.name());
                if (module != null) module.settings.reset();
            }
        });
        AutoPvp autoPvp = bench.meteor(AutoPvp.class);
        boolean applied = bench.fromClient(client -> autoPvp.useProfile(profile).ok());
        if (!applied) throw new BenchException("the " + profile + " profile was not applied");
        bench.onClient(client -> {
            if (aura.isActive()) aura.disable();
        });
        return new AutoPvpScene(autoPvp);
    }

    /** T0 with auto-pvp as the module under test; CrystalAura must still be off. */
    void start(Bench bench, boolean recorder) {
        if (bench.fromClient(client -> on(ManagedModules.CRYSTAL_AURA))) {
            throw new BenchException("crystal-aura was on before T0");
        }
        bench.start(recorder, AutoPvp.class);
    }

    /**
     * One tick as auto-pvp sees it: its reported phase (null before its first plan), its target, whether
     * its plan enables crystal-aura, the modules that are on, and what the panel lists as off by profile.
     */
    record Look(CombatState state, String target, boolean planEnablesAura, boolean auraOn, boolean cityOn,
                boolean anvilOn, List<String> profileOff) {
    }

    Look look(Bench bench) {
        return bench.fromClient(client -> {
            Optional<Plan> plan = autoPvp.currentPlan();
            return new Look(plan.map(Plan::state).orElse(null), autoPvp.currentTarget().orElse(null),
                plan.map(p -> p.enable().contains(ManagedModules.CRYSTAL_AURA)).orElse(false),
                on(ManagedModules.CRYSTAL_AURA), on(ManagedModules.AUTO_CITY), on(ManagedModules.AUTO_ANVIL),
                autoPvp.panelInput(true).profileOff());
        });
    }

    /** Whether the Meteor module auto-pvp manages under that name is on. Client thread. */
    static boolean on(ManagedModule managed) {
        Module module = Modules.get().get(managed.name());
        if (module == null) throw new BenchException("no module " + managed.name());
        return module.isActive();
    }
}
