package com.xploits.restock.core;

import com.xploits.printer.core.Guards;
import com.xploits.printer.core.PrinterLimits;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner ruling R42: which stops leave a shulker box out at once, and which finish the break and the pick-up first; and
 * what the session does with the guards' stop while an unpack runs.
 */
class UnpackStopsTest {
    @Test
    void attackedLowHealthAndTheOtherUrgentStopsNeverWait() {
        // Rulings R45/R53: a setback is an anticheat flag — more digging and walking risks more flags or a kick.
        for (RestockReason r : List.of(RestockReason.ATTACKED, RestockReason.LOW_HEALTH, RestockReason.SETBACK,
            RestockReason.AUTO_PVP_ENGAGED, RestockReason.DIED, RestockReason.DIMENSION, RestockReason.LEFT,
            RestockReason.NO_WORLD, RestockReason.MODULE_OFF, RestockReason.PLAYER_MOVED, RestockReason.INTERNAL)) {
            assertFalse(UnpackStops.finishesFirst(r), r.name());
        }
    }

    @Test
    void everyStopReasonIsOnTheSideOfTheSplitTheRulingGives() {
        Set<RestockReason> atOnce = EnumSet.of(RestockReason.ATTACKED, RestockReason.LOW_HEALTH, RestockReason.SETBACK,
            RestockReason.AUTO_PVP_ENGAGED, RestockReason.DIED, RestockReason.DIMENSION, RestockReason.LEFT,
            RestockReason.NO_WORLD, RestockReason.MODULE_OFF, RestockReason.PLAYER_MOVED, RestockReason.INTERNAL);
        for (RestockReason r : RestockReason.values()) {
            if (r.effect() != RestockReason.Effect.STOP) continue;
            assertEquals(!atOnce.contains(r), UnpackStops.finishesFirst(r), r.name());
        }
    }

    @Test
    void anyOtherStopFinishesTheBreakAndThePickUpFirst() {
        for (RestockReason r : List.of(RestockReason.PLAYER_NEAR, RestockReason.CONFLICTING_MODULE,
            RestockReason.COMBAT_REPEATED, RestockReason.OTHER_ROTATION_REPEATED, RestockReason.CLICK_NOT_SENT,
            RestockReason.BARITONE_NOT_LISTENING)) {
            assertTrue(UnpackStops.finishesFirst(r), r.name());
        }
        for (RestockReason r : RestockReason.values()) {
            if (r.effect() != RestockReason.Effect.STOP) {
                assertFalse(UnpackStops.finishesFirst(r), "not a stop: " + r.name());
            }
        }
    }

    @Test
    void whileFinishingTheUrgentOnesAreReadAgainInTheGuardsOrder() {
        // A stranger is near and a conflicting module is on in every case: neither is urgent.
        assertEquals(Optional.empty(), UnpackStops.atOnce(inputs(false, false, false, false, false, 20)));
        assertEquals(Optional.empty(), UnpackStops.atOnce(inputs(false, false, false, false, false, 10)),
            "health equal to the minimum is not low, as in the guards");
        assertEquals(Optional.of(RestockReason.SETBACK),
            UnpackStops.atOnce(inputs(false, false, false, false, true, 20)),
            "rulings R45/R53: the server set the player back");
        assertEquals(Optional.of(RestockReason.LOW_HEALTH),
            UnpackStops.atOnce(inputs(false, false, false, false, true, 9.5)),
            "low health before a setback, as in the guards");
        assertEquals(Optional.of(RestockReason.LOW_HEALTH),
            UnpackStops.atOnce(inputs(false, false, false, false, false, Double.NaN)),
            "NaN counts as low, as in the guards");
        assertEquals(Optional.of(RestockReason.ATTACKED),
            UnpackStops.atOnce(inputs(false, false, false, true, false, 5)));
        assertEquals(Optional.of(RestockReason.AUTO_PVP_ENGAGED),
            UnpackStops.atOnce(inputs(false, false, true, true, false, 5)));
        assertEquals(Optional.of(RestockReason.DIMENSION),
            UnpackStops.atOnce(inputs(false, true, true, true, false, 5)));
        assertEquals(Optional.of(RestockReason.DIED), UnpackStops.atOnce(inputs(true, true, true, true, true, 5)));
    }

    @Test
    void aStopThatFinishesFirstWaitsOnlyWithABoxOut() {
        // A stranger is near and a conflicting module is on: neither is urgent.
        Guards.Inputs calm = inputs(false, false, false, false, false, 20);
        for (RestockReason r : List.of(RestockReason.PLAYER_NEAR, RestockReason.CONFLICTING_MODULE,
            RestockReason.COMBAT_REPEATED, RestockReason.OTHER_ROTATION_REPEATED)) {
            assertEquals(new UnpackStops.Drain(r), UnpackStops.onGuardStop(r, true, false, calm), r.name());
            assertEquals(new UnpackStops.Halt(r), UnpackStops.onGuardStop(r, false, false, calm),
                "no box out (yet, or any more): " + r.name());
        }
    }

    @Test
    void anAtOnceStopHaltsWithABoxOutWithItsOwnName() {
        // Whatever the inputs say: the guards' own reason decides the side of the split.
        Guards.Inputs calm = inputs(false, false, false, false, false, 20);
        for (RestockReason r : List.of(RestockReason.ATTACKED, RestockReason.LOW_HEALTH, RestockReason.SETBACK,
            RestockReason.AUTO_PVP_ENGAGED, RestockReason.DIED, RestockReason.DIMENSION)) {
            assertEquals(new UnpackStops.Halt(r), UnpackStops.onGuardStop(r, true, false, calm), r.name());
        }
        assertEquals(new UnpackStops.Halt(RestockReason.ATTACKED), UnpackStops.onGuardStop(RestockReason.ATTACKED, true,
            false, inputs(false, false, false, true, false, 20)));
    }

    @Test
    void anAtOnceReasonInTheTickAStrangerCameNearHaltsTheUnpack() {
        // The guards name only their first stop: a stranger near comes before a setback, and the setback is a one-tick
        // pulse the next tick no longer shows (rulings R45/R53). The stop keeps the guards' own name.
        assertEquals(new UnpackStops.Halt(RestockReason.PLAYER_NEAR), UnpackStops.onGuardStop(RestockReason.PLAYER_NEAR,
            true, false, inputs(false, false, false, false, true, 20)));
    }

    @Test
    void whileFinishingOnlyAnAtOnceReasonReadAgainHaltsAndWithItsOwnName() {
        RestockReason held = RestockReason.PLAYER_NEAR;
        assertEquals(new UnpackStops.Carry(), UnpackStops.onGuardStop(held, true, true,
            inputs(false, false, false, false, false, 20)), "a stranger near, a conflicting module: still finishing");
        assertEquals(new UnpackStops.Carry(), UnpackStops.onGuardStop(held, false, true,
            inputs(false, false, false, false, false, 20)), "the box back in the inventory: the unpack ends by itself");
        assertEquals(new UnpackStops.Halt(RestockReason.ATTACKED), UnpackStops.onGuardStop(held, true, true,
            inputs(false, false, false, true, false, 20)));
        assertEquals(new UnpackStops.Halt(RestockReason.SETBACK), UnpackStops.onGuardStop(held, true, true,
            inputs(false, false, false, false, true, 20)));
        assertEquals(new UnpackStops.Halt(RestockReason.LOW_HEALTH), UnpackStops.onGuardStop(held, true, true,
            inputs(false, false, false, false, true, 9.5)), "in the guards' order: low health before a setback");
        assertEquals(new UnpackStops.Halt(RestockReason.DIED), UnpackStops.onGuardStop(RestockReason.CONFLICTING_MODULE,
            true, true, inputs(true, true, true, true, true, 5)));
    }

    @Test
    void whileFinishingLagEatingAndSomeoneElsesActionHoldEveryClick() {
        assertFalse(UnpackStops.holds(guard(0.1, false, false), PrinterLimits.DEFAULTS));
        assertTrue(UnpackStops.holds(guard(1.5, false, false), PrinterLimits.DEFAULTS),
            "the guards' lag pause starts at 1.5 s");
        assertFalse(UnpackStops.holds(guard(1.49, false, false), PrinterLimits.DEFAULTS));
        assertTrue(UnpackStops.holds(guard(0.1, true, false), PrinterLimits.DEFAULTS));
        assertTrue(UnpackStops.holds(guard(0.1, false, true), PrinterLimits.DEFAULTS));
    }

    private static Guards.Inputs inputs(boolean died, boolean dimension, boolean autoPvp, boolean attacked,
                                        boolean setback, double health) {
        return new Guards.Inputs(0.1, false, false, List.of(), autoPvp, true, true, attacked, health, 10, setback, null,
            List.of("scaffold"), false, died, dimension, false);
    }

    private static Guards.Inputs guard(double secondsSinceServerTick, boolean eating, boolean acting) {
        return new Guards.Inputs(secondsSinceServerTick, eating, acting, List.of(), false, false, true, false, 20, 10,
            false, null, List.of(), false, false, false, false);
    }
}
