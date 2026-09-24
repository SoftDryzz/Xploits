package com.xploits.travel.core;

import com.xploits.travel.core.BorrowedModule.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorrowedModuleTest {
    /** elytra-fly: the preparation always turns it off (spec §6.2, step 5). */
    private static BorrowedModule elytraFly() {
        return new BorrowedModule("elytra-fly", false);
    }

    /** elytra-replace: the preparation always turns it on (spec §6.2, step 5). */
    private static BorrowedModule elytraReplace() {
        return new BorrowedModule("elytra-replace", true);
    }

    @Test
    void takingAModuleThatIsInTheWayTurnsItOffAndLandingGivesItBack() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.TURN_OFF, module.take(true));
        assertEquals(Action.TURN_ON, module.release(false, true));
    }

    @Test
    void takingAModuleThatIsMissingTurnsItOnAndLandingGivesItBack() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.TURN_ON, module.take(false));
        assertEquals(Action.TURN_OFF, module.release(true, true));
    }

    /**
     * The case that started all this: a player who never uses elytra-fly cannot land with elytra-fly
     * on. With the resting state declared in a setting whose value was true, they landed with it on.
     */
    @Test
    void aModuleThePlayerNeverUsedIsNotTurnedOnByTheLanding() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.NONE, module.take(false), "it was already off: there is nothing to turn off");
        assertEquals(Action.NONE, module.release(false, true), "it was off before and it is still off");
    }

    @Test
    void aModuleThatWasAlreadyOnStaysOn() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.NONE, module.take(true));
        assertEquals(Action.NONE, module.release(true, true));
    }

    /** ModuleLedger's doctrine: what the player moves by hand overrides what was noted down. */
    @Test
    void aManualMoveDuringTheFlightWins() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.TURN_OFF, module.take(true));
        // The player turns it back on mid-flight: on landing it is not touched.
        assertEquals(Action.NONE, module.release(true, true));
        assertFalse(module.hasPending());
    }

    @Test
    void aManualMoveDuringTheFlightWinsTheOtherWayRound() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.TURN_ON, module.take(false));
        // The player turns it off mid-flight: on landing it is neither turned off again nor turned on.
        assertEquals(Action.NONE, module.release(false, true));
    }

    /**
     * The two tests above do NOT protect the manual-move rule, even though it looks like they do: in
     * both the player puts the module back to its take-off state, so the next check -"it is already
     * where it was"- returns NONE on its own and the test passes the same with the rule as without it.
     * This was checked by deleting the line: the whole suite stayed green.
     *
     * The two below are the only cases that tell them apart: the module stays in the state the
     * preparation did NOT touch, and the player moves it the other way. Without the rule, the landing
     * undoes the player's change and on top of that reports that it restored the environment.
     */
    @Test
    void aManualMoveWinsEvenWhenThePreparationNeverTouchedTheModule() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.NONE, module.take(false), "it was already off: the preparation does not touch it");

        // The player turns elytra-fly on by hand mid-flight. On landing it is theirs, not ours:
        // without the rule it would be turned off, because the note says "it was off".
        assertEquals(Action.NONE, module.release(true, true));
        assertFalse(module.hasPending(), "there is nothing to give back: the module is the player's");
    }

    @Test
    void aManualMoveWinsEvenWhenThePreparationNeverTouchedTheModuleTheOtherWayRound() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.NONE, module.take(true), "it was already on: the preparation does not touch it");

        // The player turns elytra-replace off by hand during the flight. Without the rule it would be
        // turned back on on landing, because the note says "it was on".
        assertEquals(Action.NONE, module.release(false, true));
        assertFalse(module.hasPending());
    }

    @Test
    void releasingWithoutHavingTakenDoesNothing() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.NONE, module.release(true, true));
        assertEquals(Action.NONE, module.release(false, true));
        assertFalse(module.hasPending());
    }

    /** Leaving the world: no module can be touched, so the give-back stays pending. */
    @Test
    void whenTheModuleCannotBeToggledTheDecisionIsLeftPending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        assertEquals(Action.NONE, module.release(false, false), "nothing is touched during the teardown");
        assertTrue(module.hasPending());
        assertEquals(Action.TURN_ON, module.pending());
    }

    @Test
    void claimingThePendingHandsItOverOnlyOnce() {
        BorrowedModule module = elytraReplace();
        module.take(false);
        module.release(true, false);
        assertEquals(Action.TURN_OFF, module.claimPending());
        assertFalse(module.hasPending());
        assertEquals(Action.NONE, module.claimPending());
    }

    /**
     * The six exit paths overlap (spec §6.3): turning the module off arrives right behind leaving the
     * world. The second cannot erase what the first noted down.
     */
    @Test
    void aSecondReleaseDoesNotWipeThePending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        module.release(false, false);
        assertEquals(Action.NONE, module.release(false, true), "it is no longer borrowed");
        assertTrue(module.hasPending());
        assertEquals(Action.TURN_ON, module.pending());
    }

    @Test
    void withNothingToUndoTheresNoPendingEither() {
        BorrowedModule module = elytraFly();
        module.take(false);
        assertEquals(Action.NONE, module.release(false, false));
        assertFalse(module.hasPending(), "there was nothing to give back: there is nothing to leave pending");
    }

    /** A new trip notes down from scratch: the previous one's pending action no longer describes any resting state. */
    @Test
    void takingAgainForgetsThePending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        module.release(false, false);
        assertTrue(module.hasPending());
        module.take(true);
        assertFalse(module.hasPending());
    }

    @Test
    void forgetDropsTheNoteAndThePending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        module.release(false, false);
        module.forget();
        assertFalse(module.hasPending());
        assertEquals(Action.NONE, module.release(false, true));
    }

    @Test
    void theInFlightStateIsTheDeclaredOne() {
        assertFalse(elytraFly().inFlight());
        assertTrue(elytraReplace().inFlight());
        assertEquals("elytra-fly", elytraFly().name());
        assertEquals("elytra-replace", elytraReplace().name());
    }
}
