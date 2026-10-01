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
            "highway-builder", "liquid-filler", "excavator", "infinity-miner", "echest-farmer"), Guards.CONFLICTING_MODULES);
    }
}
