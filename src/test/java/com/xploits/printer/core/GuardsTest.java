package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.xploits.printer.core.Guards.Reason;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Printer spec §5.8 and §5.3: refusals, stops, pauses, and the combat yield. */
class GuardsTest {
    /** A calm tick, field by field, to vary one at a time. */
    private static final class In {
        double lag = 0.05;
        boolean eating;
        boolean acting;
        List<String> combat = List.of();
        boolean engaged;
        boolean near;
        boolean stopNear = true;
        boolean attacked;
        double health = 20;
        double minHealth = 10;
        boolean setback;
        Reason source;
        List<String> conflicting = List.of();
        boolean notSent;
        boolean died;
        boolean dimension;
        boolean mismatch;

        Guards.Inputs build() {
            return new Guards.Inputs(lag, eating, acting, combat, engaged, near, stopNear, attacked, health, minHealth,
                setback, source, conflicting, notSent, died, dimension, mismatch);
        }
    }

    private static Guards guards() {
        return new Guards(PrinterLimits.DEFAULTS);
    }

    @Test
    void aCalmTickRuns() {
        assertEquals(new Guards.Run(false), guards().tick(new In().build()));
    }

    @Test
    void stopsComeInTheDocumentedOrder() {
        In in = new In();
        in.died = true;
        in.setback = true;
        in.near = true;
        assertEquals(new Guards.Stop(Reason.DIED, ""), guards().tick(in.build()));
        in.died = false;
        assertEquals(new Guards.Stop(Reason.PLAYER_NEAR, ""), guards().tick(in.build()));
        in.near = false;
        assertEquals(new Guards.Stop(Reason.SETBACK, ""), guards().tick(in.build()));
    }

    @Test
    void eachStopAlone() {
        In a = new In();
        a.engaged = true;
        assertEquals(Reason.AUTO_PVP_ENGAGED, ((Guards.Stop) guards().tick(a.build())).reason());
        In b = new In();
        b.attacked = true;
        assertEquals(Reason.ATTACKED, ((Guards.Stop) guards().tick(b.build())).reason());
        In c = new In();
        c.dimension = true;
        assertEquals(Reason.DIMENSION, ((Guards.Stop) guards().tick(c.build())).reason());
        In d = new In();
        d.source = Reason.LAYER_RANGE_CHANGED;
        assertEquals(Reason.LAYER_RANGE_CHANGED, ((Guards.Stop) guards().tick(d.build())).reason());
        In e = new In();
        e.conflicting = List.of("scaffold", "auto-tool");
        assertEquals(new Guards.Stop(Reason.CONFLICTING_MODULE, "scaffold, auto-tool"), guards().tick(e.build()));
        In f = new In();
        f.notSent = true;
        assertEquals(Reason.PLACEMENT_NOT_SENT, ((Guards.Stop) guards().tick(f.build())).reason());
        In g = new In();
        g.mismatch = true;
        assertEquals(Reason.BREAK_SPEED_MISMATCH, ((Guards.Stop) guards().tick(g.build())).reason());
    }

    @Test
    void healthAtTheMinimumStillRuns() {
        In in = new In();
        in.health = 10;
        assertEquals(new Guards.Run(false), guards().tick(in.build()));
        in.health = 9.9;
        assertEquals(new Guards.Stop(Reason.LOW_HEALTH, ""), guards().tick(in.build()));
    }

    @Test
    void aNearPlayerIsIgnoredWithTheSettingOff() {
        In in = new In();
        in.near = true;
        in.stopNear = false;
        assertEquals(new Guards.Run(false), guards().tick(in.build()));
    }

    @Test
    void lagAndEatingPauseAndEndByThemselves() {
        Guards g = guards();
        In in = new In();
        in.lag = 1.49;
        assertEquals(new Guards.Run(false), g.tick(in.build()));
        in.lag = 1.5;
        assertEquals(new Guards.Pause(Reason.LAG, ""), g.tick(in.build()));
        in.lag = 0.05;
        in.eating = true;
        assertEquals(new Guards.Pause(Reason.EATING, ""), g.tick(in.build()));
        in.eating = false;
        assertEquals(new Guards.Run(false), g.tick(in.build()));
    }

    @Test
    void combatYieldsThenPausesThenEndsThePauseThenStopsOnTheThird() {
        Guards g = guards();
        In acting = new In();
        acting.acting = true;
        acting.combat = List.of("surround++");
        In clear = new In();
        clear.combat = List.of("surround++");
        for (int round = 1; round <= 2; round++) {
            for (int i = 1; i <= 19; i++) assertEquals(new Guards.Run(true), g.tick(acting.build()), "yield " + i);
            assertEquals(new Guards.Pause(Reason.COMBAT, "surround++"), g.tick(acting.build()));
            for (int i = 1; i <= 39; i++) assertEquals(new Guards.Pause(Reason.COMBAT, "surround++"), g.tick(clear.build()));
            assertEquals(new Guards.Run(false), g.tick(clear.build()), "the 40th clear tick ends the pause");
            assertEquals(round, g.combatPauses());
        }
        for (int i = 1; i <= 19; i++) g.tick(acting.build());
        assertEquals(new Guards.Stop(Reason.COMBAT_REPEATED, "surround++"), g.tick(acting.build()));
    }

    @Test
    void anActingTickDuringThePauseStartsTheClearCountAgain() {
        Guards g = guards();
        In acting = new In();
        acting.acting = true;
        In clear = new In();
        for (int i = 1; i <= 20; i++) g.tick(acting.build());
        for (int i = 1; i <= 30; i++) g.tick(clear.build());
        g.tick(acting.build());
        for (int i = 1; i <= 39; i++) assertEquals(Reason.OTHER_ROTATION, ((Guards.Pause) g.tick(clear.build())).reason());
        assertEquals(new Guards.Run(false), g.tick(clear.build()));
    }

    @Test
    void anActingTickWithNoCombatModuleYieldsThenPausesNamingNoModuleAndStopsOnTheThird() {
        Guards g = guards();
        In acting = new In();
        acting.acting = true;
        In clear = new In();
        for (int round = 1; round <= 2; round++) {
            for (int i = 1; i <= 19; i++) assertEquals(new Guards.Run(true), g.tick(acting.build()), "yield " + i);
            assertEquals(new Guards.Pause(Reason.OTHER_ROTATION, ""), g.tick(acting.build()));
            for (int i = 1; i <= 39; i++) assertEquals(new Guards.Pause(Reason.OTHER_ROTATION, ""), g.tick(clear.build()));
            assertEquals(new Guards.Run(false), g.tick(clear.build()), "the 40th clear tick ends the pause");
            assertEquals(round, g.combatPauses());
        }
        for (int i = 1; i <= 19; i++) g.tick(acting.build());
        assertEquals(new Guards.Stop(Reason.OTHER_ROTATION_REPEATED, ""), g.tick(acting.build()));
    }

    @Test
    void anInterruptedStreakNeverPauses() {
        Guards g = guards();
        In acting = new In();
        acting.acting = true;
        for (int k = 0; k < 5; k++) {
            for (int i = 1; i <= 19; i++) g.tick(acting.build());
            assertEquals(new Guards.Run(false), g.tick(new In().build()));
        }
        assertEquals(0, g.combatPauses());
    }

    private static Guards.EnableInputs enable(boolean world, boolean alive, boolean camera, boolean riding, boolean queue,
                                              Guards.Refusal source, boolean travel, boolean sweep, List<String> conflicting,
                                              boolean engaged, boolean near, double health, boolean baritone,
                                              boolean prefix) {
        return new Guards.EnableInputs(world, alive, camera, riding, queue, source, travel, sweep, conflicting, engaged,
            near, true, health, 10, baritone, prefix);
    }

    @Test
    void refusalsComeInTheTablesOrder() {
        assertEquals(Optional.empty(), Guards.refuse(enable(true, true, true, false, true, null, false, false, List.of(),
            false, false, 20, true, true)));
        assertEquals(Reason.NO_WORLD, Guards.refuse(enable(false, false, false, true, false, null, true, true,
            List.of("scaffold"), true, true, 1, true, false)).orElseThrow().reason());
        assertEquals(Reason.CAMERA_NOT_PLAYER, Guards.refuse(enable(true, true, false, false, true, null, false, false,
            List.of(), false, false, 20, false, true)).orElseThrow().reason());
        assertEquals(Reason.METEOR_API, Guards.refuse(enable(true, true, true, false, false, null, false, false,
            List.of(), false, false, 20, false, true)).orElseThrow().reason());
        Guards.Refusal easy = new Guards.Refusal(Reason.EASY_PLACE_RESTRICTION, "");
        assertEquals(Optional.of(easy), Guards.refuse(enable(true, true, true, false, true, easy, true, false,
            List.of(), false, false, 20, false, true)));
        assertEquals(Reason.TRAVEL_RUNNING, Guards.refuse(enable(true, true, true, false, true, null, true, true,
            List.of(), false, false, 20, false, true)).orElseThrow().reason());
        assertEquals(new Guards.Refusal(Reason.CONFLICTING_MODULE, "nuker"), Guards.refuse(enable(true, true, true,
            false, true, null, false, false, List.of("nuker"), true, false, 20, false, true)).orElseThrow());
        assertEquals(Reason.LOW_HEALTH, Guards.refuse(enable(true, true, true, false, true, null, false, false,
            List.of(), false, false, 9, false, true)).orElseThrow().reason());
        assertEquals(Reason.PREFIX_INVALID, Guards.refuse(enable(true, true, true, false, true, null, false, false,
            List.of(), false, false, 20, true, false)).orElseThrow().reason());
        assertEquals(Optional.empty(), Guards.refuse(enable(true, true, true, false, true, null, false, false,
            List.of(), false, false, 20, false, false)), "an unusable prefix matters only with Baritone");
    }

    /** Each running stop, set on an otherwise calm tick; the reason it must give. */
    private static List<java.util.function.Consumer<In>> allStops() {
        return List.of(i -> i.died = true, i -> i.dimension = true, i -> i.engaged = true, i -> i.attacked = true,
            i -> i.health = 1, i -> i.near = true, i -> i.setback = true, i -> i.source = Reason.PLACEMENT_CHANGED,
            i -> i.conflicting = List.of("nuker"), i -> i.notSent = true, i -> i.mismatch = true);
    }

    @Test
    void everyStopBeatsLagEatingAndAYieldStreak() {
        for (java.util.function.Consumer<In> stop : allStops()) {
            Guards g = guards();
            In acting = new In();
            acting.acting = true;
            for (int i = 1; i <= 19; i++) g.tick(acting.build());
            In in = new In();
            in.acting = true;
            in.lag = 2.0;
            in.eating = true;
            stop.accept(in);
            assertEquals(Guards.Effect.STOP, ((Guards.Stop) g.tick(in.build())).reason().effect());
        }
        In lagAlone = new In();
        lagAlone.lag = 2.0;
        lagAlone.eating = true;
        assertEquals(new Guards.Pause(Reason.LAG, ""), guards().tick(lagAlone.build()));
    }

    @Test
    void everyStopBeatsARunningCombatPause() {
        for (java.util.function.Consumer<In> stop : allStops()) {
            Guards g = guards();
            In acting = new In();
            acting.acting = true;
            acting.combat = List.of("surround++");
            for (int i = 1; i <= 20; i++) g.tick(acting.build());
            In in = new In();
            in.combat = List.of("surround++");
            stop.accept(in);
            assertEquals(Guards.Effect.STOP, ((Guards.Stop) g.tick(in.build())).reason().effect());
        }
    }

    @Test
    void aPauseKeepsItsNameUntilItEnds() {
        Guards g = guards();
        In acting = new In();
        acting.acting = true;
        acting.combat = List.of("surround++");
        for (int i = 1; i <= 20; i++) g.tick(acting.build());
        In clearOther = new In();
        assertEquals(new Guards.Pause(Reason.COMBAT, "surround++"), g.tick(clearOther.build()));
        In clearNamed = new In();
        clearNamed.combat = List.of("kill-aura");
        assertEquals(new Guards.Pause(Reason.COMBAT, "surround++"), g.tick(clearNamed.build()));

        Guards h = guards();
        In other = new In();
        other.acting = true;
        for (int i = 1; i <= 20; i++) h.tick(other.build());
        assertEquals(new Guards.Pause(Reason.OTHER_ROTATION, ""), h.tick(clearNamed.build()));
    }

    @Test
    void aStopIsLatched() {
        Guards g = guards();
        In died = new In();
        died.died = true;
        Guards.Verdict stop = g.tick(died.build());
        assertEquals(new Guards.Stop(Reason.DIED, ""), stop);
        assertEquals(stop, g.tick(new In().build()));
        In other = new In();
        other.setback = true;
        assertEquals(stop, g.tick(other.build()));
    }

    @Test
    void nanHealthStopsAndRefuses() {
        In in = new In();
        in.health = Double.NaN;
        assertEquals(new Guards.Stop(Reason.LOW_HEALTH, ""), guards().tick(in.build()));
        assertEquals(Reason.LOW_HEALTH, Guards.refuse(enable(true, true, true, false, true, null, false, false,
            List.of(), false, false, Double.NaN, false, true)).orElseThrow().reason());
    }

    private static Guards.EnableInputs enableWith(boolean alive, boolean riding, boolean sweep, boolean engaged,
                                                  boolean near, boolean stopNear) {
        return new Guards.EnableInputs(true, alive, true, riding, true, null, false, sweep, List.of(), engaged, near,
            stopNear, 20, 10, false, true);
    }

    @Test
    void eachRefusalAndTheAutoPvpRuleAlone() {
        assertEquals(Reason.DEAD, Guards.refuse(enableWith(false, false, false, false, false, true)).orElseThrow().reason());
        assertEquals(Reason.RIDING, Guards.refuse(enableWith(true, true, false, false, false, true)).orElseThrow().reason());
        assertEquals(Reason.SWEEP_RUNNING, Guards.refuse(enableWith(true, false, true, false, false, true)).orElseThrow().reason());
        assertEquals(Reason.AUTO_PVP_ENGAGED, Guards.refuse(enableWith(true, false, false, true, false, true)).orElseThrow().reason());
        assertEquals(Reason.AUTO_PVP_ENGAGED, Guards.refuse(enableWith(true, false, false, true, false, false)).orElseThrow().reason(),
            "auto-pvp refuses with stop-near-players off");
        assertEquals(Reason.PLAYER_NEAR, Guards.refuse(enableWith(true, false, false, false, true, true)).orElseThrow().reason());
        assertEquals(Optional.empty(), Guards.refuse(enableWith(true, false, false, false, true, false)),
            "a near player is no refusal with the setting off");
        In in = new In();
        in.engaged = true;
        in.stopNear = false;
        assertEquals(new Guards.Stop(Reason.AUTO_PVP_ENGAGED, ""), guards().tick(in.build()));
    }

    @Test
    void everyReasonHasItsEffect() {
        assertEquals(Guards.Effect.REFUSE, Reason.METEOR_API.effect());
        assertEquals(Guards.Effect.STOP, Reason.SETBACK.effect());
        assertEquals(Guards.Effect.PAUSE, Reason.LAG.effect());
        assertEquals(Guards.Effect.PAUSE, Reason.OTHER_ROTATION.effect());
        assertEquals(Guards.Effect.STOP, Reason.OTHER_ROTATION_REPEATED.effect());
        assertEquals(Guards.Effect.END, Reason.FINISHED.effect());
    }

    @Test
    void theModuleListsAreTheVerifiedNames() {
        assertEquals(List.of("crystal-aura++", "surround++", "crystal-aura", "surround", "auto-trap", "anchor-aura",
            "anti-bed", "anti-anvil", "anti-anchor", "hole-filler", "auto-web", "auto-anvil", "auto-city", "kill-aura",
            "bow-aimbot", "bed-aura", "self-trap", "burrow"), Guards.COMBAT_MODULES);
        assertEquals(List.of("instant-rebreak", "speed-mine", "packet-mine", "auto-tool", "anti-afk", "auto-walk",
            "auto-replenish", "inventory-tweaks", "scaffold", "air-place", "no-ghost-blocks", "nuker", "vein-miner",
            "highway-builder", "liquid-filler", "excavator", "infinity-miner", "echest-farmer", "spawn-proofer", "timer"), Guards.CONFLICTING_MODULES);
    }
}
