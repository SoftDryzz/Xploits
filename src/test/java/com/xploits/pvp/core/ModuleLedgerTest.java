package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLedgerTest {
    /**
     * One ledger tick with the posture still. Almost all these tests look at the offensive axis, and
     * for them the posture is a parameter that does not change; those that do move it (important I6)
     * call {@code ledger.apply} with it directly.
     */
    private static ModuleLedger.Result apply(ModuleLedger ledger, CombatState phase,
                                             Set<String> wanted, Set<String> active) {
        return ledger.apply(phase, CombatPosture.CALM, wanted, active);
    }

    /**
     * Observes the module off for as many ticks as the §8 debounce needs to count it as
     * released, and returns the result of the tick in which that happens.
     */
    private static ModuleLedger.Result releaseByHand(ModuleLedger ledger, CombatState phase, String name) {
        ModuleLedger.Result result = null;
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS; i++) {
            result = apply(ledger, phase, Set.of(name), Set.of());
        }
        return result;
    }

    @Test
    void aModuleTheUserTurnedOnByHandIsNeverDisabled() {
        ModuleLedger ledger = new ModuleLedger();

        // "crystal-aura" is on but the current phase does not ask for it: it is the player's, not the
        // ledger's, because it never went through its "toEnable" side.
        ModuleLedger.Result result = apply(ledger, CombatState.APPROACH, Set.of(), Set.of("crystal-aura"));

        assertTrue(result.toDisable().isEmpty(), "it is not its own: it must never turn it off");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void aTakenModuleTheUserTurnsOffByHandStopsBeingOursAndIsNotRetakenInThatPhase() {
        ModuleLedger ledger = new ModuleLedger();

        // Tick 1: the phase asks for crystal-aura, nothing on yet -> the ledger turns it on and takes it.
        ModuleLedger.Result first = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), first.toEnable());
        assertTrue(ledger.owned().contains("crystal-aura"));

        // The player turns it off by hand and leaves it off: once the debounce is over, it is theirs.
        ModuleLedger.Result released = releaseByHand(ledger, CombatState.SURFACE, "crystal-aura");
        assertEquals(List.of("crystal-aura"), released.newlyReleased());
        assertTrue(released.toEnable().isEmpty(), "it must not turn it back on in the tick it was released");
        assertFalse(ledger.owned().contains("crystal-aura"));

        // Same phase: it still does not take it even though the phase still asks for it.
        ModuleLedger.Result next = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertTrue(next.toEnable().isEmpty(), "released by hand: not taken back in the same phase");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void releasingTwiceInARowDoesNotDisableAgainOrRepeatTheNotice() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        releaseByHand(ledger, CombatState.SURFACE, "crystal-aura");

        // Two more ticks without anything changing: it never asks for the shutdown again nor repeats the warning.
        for (int i = 0; i < 2; i++) {
            ModuleLedger.Result result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
            assertTrue(result.toDisable().isEmpty(), "it is already off: there is nothing to turn off again");
            assertTrue(result.newlyReleased().isEmpty(), "the release warning was already given once");
        }
    }

    @Test
    void aPhaseChangeForgetsWhatWasReleasedByHand() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        releaseByHand(ledger, CombatState.SURFACE, "crystal-aura");
        assertFalse(ledger.owned().contains("crystal-aura"));

        // The phase changes (for example to SURROUNDED, which also asks for crystal-aura) and asks for it again:
        // now it is taken back, because changing phase forgets what was released by hand.
        ModuleLedger.Result result = apply(ledger, CombatState.SURROUNDED, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable());
        assertTrue(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void aModuleNoLongerWantedIsDisabledAndDropped() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of("crystal-aura")); // already active

        // The phase stops asking for it: it is turned off because it is ours, not because the player released it.
        ModuleLedger.Result result = apply(ledger, CombatState.APPROACH, Set.of(), Set.of("crystal-aura"));
        assertEquals(List.of("crystal-aura"), result.toDisable());
        assertTrue(result.newlyReleased().isEmpty(), "this is not a release by hand");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void theAlwaysOnModulesNeverAppearAsOwnedOrReleased() {
        ModuleLedger ledger = new ModuleLedger();

        // auto-totem, auto-armor, offhand and auto-weapon never get into "wanted": the director does not
        // manage them (spec §7). Even if they are active, the ledger must not touch them at all.
        Set<String> active = Set.of("auto-totem", "auto-armor", "offhand", "auto-weapon", "crystal-aura");
        ModuleLedger.Result result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), active);

        assertTrue(result.toDisable().isEmpty());
        assertTrue(result.newlyReleased().isEmpty());
        assertFalse(ledger.owned().contains("auto-totem"));
        assertFalse(ledger.owned().contains("auto-armor"));
        assertFalse(ledger.owned().contains("offhand"));
        assertFalse(ledger.owned().contains("auto-weapon"));
    }

    @Test
    void resetForgetsOwnershipAndReleases() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        releaseByHand(ledger, CombatState.SURFACE, "crystal-aura");
        assertFalse(ledger.owned().contains("crystal-aura"));

        ledger.reset();

        // Without the reset, staying in the same phase would not have taken it back; with the reset, it does.
        ModuleLedger.Result result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable());
    }

    @Test
    void resetForgetsAnUnfinishedDebounce() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of()); // one tick off

        ledger.reset();

        ModuleLedger.Result result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable(), "the half-done count does not survive the reset");
    }

    @Test
    void aSteadyStateModuleIsNeitherReenabledNorDisabled() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());

        // Still wanted, still on, still ours: there is nothing to do.
        ModuleLedger.Result result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of("crystal-aura"));
        assertTrue(result.toEnable().isEmpty());
        assertTrue(result.toDisable().isEmpty());
        assertTrue(ledger.owned().contains("crystal-aura"));
    }

    // --- CRITICAL B: a shutdown by Meteor itself is not a release by hand (spec §7) ---

    @Test
    void aSelfOffModuleThatTurnsItselfOffIsRetakenAndDoesNotWarn() {
        ModuleLedger ledger = new ModuleLedger();

        // Tick 1: the phase asks for auto-trap, nothing on yet -> the ledger turns it on and takes it.
        ModuleLedger.Result first = apply(ledger, CombatState.SURFACE, Set.of("auto-trap"), Set.of());
        assertEquals(List.of("auto-trap"), first.toEnable());
        assertTrue(ledger.owned().contains("auto-trap"));

        // Tick 2: auto-trap turned itself off (self-toggle, after placing the trap) - the phase still
        // asks for it. Since it is a module that turns itself off, this is NOT a release by hand: it must not
        // block the phase nor produce a warning, and the director must be able to take it again. Nor does it
        // go through the debounce: waiting four ticks to re-place the trap would be waiting too long.
        ModuleLedger.Result second = apply(ledger, CombatState.SURFACE, Set.of("auto-trap"), Set.of());
        assertTrue(second.newlyReleased().isEmpty(), "a shutdown by Meteor itself is not a release by hand");
        assertEquals(List.of("auto-trap"), second.toEnable(), "the director must be able to turn it back on in the same tick");
        assertTrue(ledger.owned().contains("auto-trap"));
    }

    @Test
    void crystalAuraTurnedOffByHandStaysBlockedAndWarns() {
        ModuleLedger ledger = new ModuleLedger();

        // Tick 1: takes crystal-aura, which does NOT turn itself off (turnsItselfOff() == false).
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertTrue(ledger.owned().contains("crystal-aura"));

        ModuleLedger.Result released = releaseByHand(ledger, CombatState.SURFACE, "crystal-aura");
        assertEquals(List.of("crystal-aura"), released.newlyReleased());
        assertTrue(released.toEnable().isEmpty(), "released by hand: not taken back in the same tick");
        assertFalse(ledger.owned().contains("crystal-aura"));

        ModuleLedger.Result next = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertTrue(next.toEnable().isEmpty(), "it stays blocked for the rest of the phase");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void theSelfOffMarkDoesNotChangeOwnedOrReleasedOrSteadyStateBehaviour() {
        ModuleLedger ledger = new ModuleLedger();

        // auto-anvil turns itself off, but while it stays on and keeps being asked for, the
        // "taken" and "steady state" behaviour is identical to that of a normal module.
        ModuleLedger.Result first = apply(ledger, CombatState.BURROWED, Set.of("auto-anvil"), Set.of());
        assertEquals(List.of("auto-anvil"), first.toEnable());
        assertTrue(ledger.owned().contains("auto-anvil"));

        ModuleLedger.Result steady = apply(ledger, CombatState.BURROWED, Set.of("auto-anvil"), Set.of("auto-anvil"));
        assertTrue(steady.toEnable().isEmpty());
        assertTrue(steady.toDisable().isEmpty());
        assertTrue(ledger.owned().contains("auto-anvil"));

        // And when the phase stops asking for it while it is still active, it is turned off like any other
        // taken module -this is not a "release", it is the phase letting go of it.
        ModuleLedger.Result noLongerWanted = apply(ledger, CombatState.NO_COMBAT, Set.of(), Set.of("auto-anvil"));
        assertEquals(List.of("auto-anvil"), noLongerWanted.toDisable());
        assertTrue(noLongerWanted.newlyReleased().isEmpty());
        assertFalse(ledger.owned().contains("auto-anvil"));
    }

    // --- §8: a flicker is not a release ---

    @Test
    void aSingleOffTickDoesNotReleaseTheModule() {
        // §11: a one-tick "off" does not release the module. A double press of a bind left you
        // without it in the middle of combat.
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());

        ModuleLedger.Result blip = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());

        assertTrue(blip.newlyReleased().isEmpty(), "one tick off is not a release");
        assertTrue(ledger.owned().contains("crystal-aura"), "it is still ours while the debounce lasts");
        assertTrue(blip.toEnable().isEmpty(),
            "nor is it turned back on: fighting the bind would keep the count from ever going up");
    }

    @Test
    void aDoublePressComesBackWithoutLosingOwnership() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());

        // Off for two ticks (the gap of a human double press) and turned on again by
        // the player themselves.
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        ModuleLedger.Result back = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of("crystal-aura"));

        assertTrue(back.newlyReleased().isEmpty());
        assertTrue(back.toDisable().isEmpty());
        assertTrue(ledger.owned().contains("crystal-aura"));

        // And the count goes back to zero: another single flicker does not release it either.
        ModuleLedger.Result another = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertTrue(another.newlyReleased().isEmpty(), "the debounce resets when it is seen on again");
    }

    @Test
    void anOffThatLastsTheWholeDebounceDoesRelease() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());

        ModuleLedger.Result result = null;
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS - 1; i++) {
            result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
            assertTrue(result.newlyReleased().isEmpty(), "tick " + i + ": still inside the debounce");
        }

        result = apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.newlyReleased(), "sustained, it is a release");
    }

    @Test
    void theDebounceIsFourTicks() {
        assertEquals(4, ModuleLedger.RELEASE_DEBOUNCE_TICKS,
            "0.2 s: more than a human double press, less than a crystal cycle");
    }

    @Test
    void aModuleTheUserTurnsOffAndThePhaseStopsWantingIsNotAReleaseEither() {
        ModuleLedger ledger = new ModuleLedger();
        apply(ledger, CombatState.SURFACE, Set.of("crystal-aura"), Set.of());
        assertTrue(ledger.owned().contains("crystal-aura"), "precondition: it is ours");

        // It turns off and the phase also stops asking for it: there is nothing to release nor to warn about.
        ModuleLedger.Result result = apply(ledger, CombatState.SURFACE, Set.of(), Set.of());
        assertTrue(result.newlyReleased().isEmpty());
        assertTrue(result.toDisable().isEmpty(), "it is already off");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    // --- C2: surround can be turned off by hand ---

    @Test
    void surroundCanBeTurnedOffByHandLikeAnyOtherModule() {
        // C2: with turnsItselfOff set, the §8 debounce did not apply to it: the ledger released
        // ownership and took it back on the SECOND PASS OF THE SAME TICK, so every time you
        // turned it off it came back within the same tick -twenty re-enables per second while you stayed
        // in the hole and threatened- and the only way out was to turn off the whole auto-pvp.
        ModuleLedger ledger = new ModuleLedger();
        Set<String> wanted = Set.of("surround");

        assertEquals(List.of("surround"),
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of()).toEnable());

        // The player turns it off. The next tick must NOT turn it back on.
        ModuleLedger.Result justOff =
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        assertTrue(justOff.toEnable().isEmpty(), "turning it back on is fighting the bind");

        // And once the debounce is over it counts as released and is not taken again.
        ModuleLedger.Result released = null;
        for (int i = 1; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS; i++) {
            released = ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        }
        assertEquals(List.of("surround"), released.newlyReleased());
        assertTrue(ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of())
            .toEnable().isEmpty(), "released by hand: not taken back");
    }

    @Test
    void aSingleBlinkOfSurroundIsStillNotAReleaseEither() {
        // The debounce it was missing also serves it for its own sake: a double press of a
        // bind cannot leave you without surround in the middle of combat.
        ModuleLedger ledger = new ModuleLedger();
        Set<String> wanted = Set.of("surround");
        ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());

        ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        ModuleLedger.Result back =
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of("surround"));

        assertTrue(back.newlyReleased().isEmpty(), "a flicker is not a release");
        assertTrue(ledger.owned().contains("surround"), "it is still ours");
    }

    // --- I6: each memory of releases is indexed by its own axis ---

    @Test
    void aDefensiveModuleReleasedByHandSurvivesAnOffensivePhaseChange() {
        // I6: releasedThisPhase was indexed by the OFFENSIVE PHASE, even for the defensive ones.
        // You turned hole-filler off by hand because it was spending your obsidian, the enemy moved one
        // block away, the offensive phase changed -nothing of yours had changed- and the ledger forgot you
        // had released it and turned it back on.
        ModuleLedger ledger = new ModuleLedger();
        Set<String> wanted = Set.of("hole-filler");
        ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS; i++) {
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        }
        assertFalse(ledger.owned().contains("hole-filler"), "precondition: it was released by hand");

        ModuleLedger.Result afterPhaseChange =
            ledger.apply(CombatState.APPROACH, CombatPosture.THREATENED, wanted, Set.of());
        assertTrue(afterPhaseChange.toEnable().isEmpty(),
            "the offensive phase does not rule over a defensive module: it is still yours");
    }

    @Test
    void aDefensiveModuleReleasedByHandIsForgottenWhenThePostureChanges() {
        // The other side: the posture is the "phase" of the defensive axis. When it changes, the situation
        // that made you release it is no longer the same and the ledger can take it again.
        ModuleLedger ledger = new ModuleLedger();
        Set<String> wanted = Set.of("hole-filler");
        ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS; i++) {
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        }

        ledger.apply(CombatState.SURFACE, CombatPosture.CALM, Set.of(), Set.of());
        ModuleLedger.Result again =
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        assertEquals(List.of("hole-filler"), again.toEnable());
    }

    @Test
    void anOffensiveModuleReleasedByHandIsNotForgottenWhenThePostureChanges() {
        // And the other way round: the posture does not rule over the offensive axis.
        ModuleLedger ledger = new ModuleLedger();
        Set<String> wanted = Set.of("crystal-aura");
        ledger.apply(CombatState.SURFACE, CombatPosture.CALM, wanted, Set.of());
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS; i++) {
            ledger.apply(CombatState.SURFACE, CombatPosture.CALM, wanted, Set.of());
        }
        assertFalse(ledger.owned().contains("crystal-aura"), "precondition: it was released by hand");

        ModuleLedger.Result afterPostureChange =
            ledger.apply(CombatState.SURFACE, CombatPosture.THREATENED, wanted, Set.of());
        assertTrue(afterPostureChange.toEnable().isEmpty(),
            "you moving to THREATENED changes nothing about what you decided on the aura");
    }
}
