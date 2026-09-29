package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertBreaks;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task C2 (final review I1): a placement whose wait ran out (late, Q2) still counts in the budget until its crystal
 * appears or the late window ends, and the crystal that then appears at its spot is still ours for the floor:
 * breaking it must leave {@code health - I - self >= FLOOR}, with or without anti-suicide.
 */
class CrystalBrainLateOwnTest {
    /** Places on base 9 at tick 1 (self {@code self}), then runs empty pre-ticks at full health up to {@code last}. */
    private static CrystalBrain placedAtTick1(double self, long last) {
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, self)).build()));
        b.placed(9, 0);
        for (long t = 2; t <= last; t++) b.preTick(DEFAULTS, tick(t).build());
        return b;
    }

    // A late placement counts

    @Test
    void aLatePlacementBlocksASecondOneThatOnlyFitsWithoutIt() {
        // Health 9, reserve 3.5. Alone: 9 - 3 = 6 >= 3.5. With the late one counted: 9 - 3 - 3 = 3 < 3.5.
        CrystalBrain late = placedAtTick1(3, 7);
        assertNothing(late.preTick(DEFAULTS, tick(8).health(9).candidates(spot(10, 8, 3)).build()));
        assertTrue(late.holding());
        assertEquals(Reason.OVER_RESERVE, late.lastDecision().reason());

        // Control: with no placement at all the same spot goes.
        assertPlaces(10, new CrystalBrain().preTick(DEFAULTS, tick(8).health(9).candidates(spot(10, 8, 3)).build()));
    }

    @Test
    void thePendingPlacementBlocksToo() {
        // Baseline for the test above: while still pending the same second spot is refused.
        CrystalBrain pending = placedAtTick1(3, 4);
        assertNothing(pending.preTick(DEFAULTS, tick(5).health(9).candidates(spot(10, 8, 3)).build()));
    }

    @Test
    void theLateWindowEndFreesTheBudget() {
        // The placement expired at pre-tick 6; it is remembered for LATE_OWN_WINDOW pre-ticks from there.
        long end = 6 + CrystalBrain.LATE_OWN_WINDOW;
        // At the window's last tick it still counts; one tick later it does not.
        CrystalBrain still = placedAtTick1(3, end - 2);
        assertNothing(still.preTick(DEFAULTS, tick(end - 1).health(9).candidates(spot(10, 8, 3)).build()));
        CrystalBrain freed = placedAtTick1(3, end - 1);
        assertPlaces(10, freed.preTick(DEFAULTS, tick(end).health(9).candidates(spot(10, 8, 3)).build()));
    }

    @Test
    void placingAgainAtTheLateSpotReplacesIt() {
        // Meteor overwrites its placing spot: only one crystal can come of the two, so the late one is not counted twice.
        CrystalBrain late = placedAtTick1(3, 7);
        assertPlaces(9, late.preTick(DEFAULTS, tick(8).health(9).candidates(spot(9, 8, 3)).build()));
    }

    @Test
    void theLateShareIsDroppedOnceItsCrystalAppears() {
        // Late self 3, health 9: the crystal appears (standing, counted once as S, never twice). A second spot then
        // sees 9 - 3 - 3 = 3 < 3.5 either way, but a smaller one, self 2, sees 9 - 3 - 2 = 4 >= 3.5 (not 1 short).
        CrystalBrain b = placedAtTick1(3, 7);
        b.crystalAdded(crystal(1, 9, 0, 3), 9, HANDS);
        assertPlaces(10, b.preTick(DEFAULTS, tick(8).health(9)
            .crystals(crystal(1, 9, 0, 3)).candidates(spot(10, 8, 2)).build()));
    }

    // The crystal that lands late

    @Test
    void aLateOwnCrystalOverTheFloorIsNotBroken() {
        // 6.5 - 5 = 1.5 < 2.
        CrystalBrain b = placedAtTick1(5, 6);
        assertTrue(b.crystalAdded(crystal(1, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(1, b.lateOwnCrystals());
        assertNothing(b.preTick(DEFAULTS, tick(8).health(6.5).crystals(crystal(1, 9, 8, 5)).build()));
    }

    @Test
    void aLateOwnCrystalWithinTheFloorIsBroken() {
        // 7 - 5 = 2 >= 2 (exactly the floor).
        CrystalBrain b = placedAtTick1(5, 6);
        assertEquals(Decision.breakCrystal(1, Reason.WITHIN_BUDGET),
            b.crystalAdded(crystal(1, 9, 8, 5), 7, HANDS).orElseThrow().decision());
        assertEquals(1, b.lateOwnCrystals());

        // The pre-tick break reads the same floor (fast-break off, so the crystal is still there to break).
        CrystalSettings slow = DEFAULTS.toBuilder().fastBreak(false).build();
        for (double health : new double[] {6.5, 7}) {
            CrystalBrain pre = new CrystalBrain();
            assertPlaces(9, pre.preTick(slow, tick(1).candidates(spot(9, 8, 5)).build()));
            pre.placed(9, 0);
            for (long t = 2; t <= 6; t++) pre.preTick(slow, tick(t).build());
            assertTrue(pre.crystalAdded(slow, crystal(1, 9, 8, 5), 20, HANDS).isEmpty());
            var actions = pre.preTick(slow, tick(7).health(health).crystals(crystal(1, 9, 8, 5)).build());
            if (health >= 7) assertBreaks(1, actions);
            else assertNothing(actions);
        }
    }

    @Test
    void aLateOwnCrystalCountsWhatIsAlreadyInFlight() {
        // Another crystal of ours (self 4) was attacked at pre-tick 3 and is still in flight at 6: I = 4.
        // 10 - 4 - 5 = 1 < 2: not broken. 11 - 4 - 5 = 2: broken.
        for (double health : new double[] {10, 11}) {
            CrystalBrain b = new CrystalBrain();
            assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
            b.placed(9, 0);
            assertPlaces(20, b.preTick(DEFAULTS, tick(2).candidates(spot(20, 8, 4)).build()));
            b.placed(20, 0);
            assertTrue(b.crystalAdded(crystal(2, 20, 0, 4), 20, HANDS).isEmpty());
            assertBreaks(2, b.preTick(DEFAULTS, tick(3).crystals(crystal(2, 20, 8, 4)).build()));
            b.attackSent();
            b.preTick(DEFAULTS, tick(4).crystals(crystal(2, 20, 8, 4)).build());
            b.preTick(DEFAULTS, tick(5).crystals(crystal(2, 20, 8, 4)).build());
            b.preTick(DEFAULTS, tick(6).crystals(crystal(2, 20, 8, 4)).build());
            var action = b.crystalAdded(crystal(1, 9, 8, 5), health, HANDS);
            assertEquals(health >= 11, action.isPresent(), "health " + health);
        }
    }

    @Test
    void aLateOwnCrystalOverMaxDamageStaysUnbrokenEvenWithRoom() {
        // Meteor's max-damage stays as the outer bound for it (foreign rules): self 6.5 > 6 at health 20 is not broken.
        CrystalBrain b = placedAtTick1(6.5, 6);
        assertTrue(b.crystalAdded(crystal(1, 9, 8, 6.5), 20, HANDS).isEmpty());
    }

    @Test
    void antiSuicideOffChangesNeitherTheCountNorTheFloor() {
        CrystalSettings off = DEFAULTS.toBuilder().antiSuicide(false).build();
        CrystalBrain over = new CrystalBrain();
        assertPlaces(9, over.preTick(off, tick(1).candidates(spot(9, 8, 5)).build()));
        over.placed(9, 0);
        for (long t = 2; t <= 6; t++) over.preTick(off, tick(t).build());
        assertTrue(over.crystalAdded(off, crystal(1, 9, 8, 5), 6.5, HANDS).isEmpty());

        CrystalBrain within = new CrystalBrain();
        assertPlaces(9, within.preTick(off, tick(1).candidates(spot(9, 8, 5)).build()));
        within.placed(9, 0);
        for (long t = 2; t <= 6; t++) within.preTick(off, tick(t).build());
        assertTrue(within.crystalAdded(off, crystal(1, 9, 8, 5), 7, HANDS).isPresent());

        CrystalBrain second = new CrystalBrain();
        assertPlaces(9, second.preTick(off, tick(1).candidates(spot(9, 8, 3)).build()));
        second.placed(9, 0);
        for (long t = 2; t <= 7; t++) second.preTick(off, tick(t).build());
        assertNothing(second.preTick(off, tick(8).health(9).candidates(spot(10, 8, 3)).build()));
    }

    // A late crystal of a KILL-overridden placement is only a late own crystal: the floor, never the override

    private static final CrystalTick.Hands TOTEM = Crystals.withTotem(HANDS, true);
    private static final TargetView KILL = Crystals.playerWithHands(Crystals.ENEMY, 3, 4, false, true);
    private static final CrystalSettings LOW_MIN = CrystalBrainFinishingBlowTest.LOW_MIN_DAMAGE;

    /** A KILL-grade placement (self 100, past everything) at 6.5 health that expires; returns the brain at its late tick. */
    private static long killPlacementExpired(CrystalBrain b, CrystalSettings settings) {
        long t = CrystalBrainFinishingBlowTest.trustedEnemy(b, 1);
        assertEquals(Decision.place(3000L, Reason.FINISHING_BLOW), only(b.preTick(settings, tick(t).health(6.5).hands(TOTEM)
            .targets(KILL).candidates(spot(3000L, java.util.Map.of(Crystals.ENEMY, 6.0), 100)).build())).decision());
        b.placed(3000L, 0);
        for (long u = t + 1; u < t + 5; u++) assertNothing(b.preTick(settings, tick(u).health(6.5).hands(TOTEM).targets(KILL).build()));
        // Expires at t + 5 (late from there); the crystal lands at that pre-tick's end.
        assertNothing(b.preTick(settings, tick(t + 5).health(6.5).hands(TOTEM).targets(KILL).build()));
        return t + 6;
    }

    private static com.xploits.pvp.crystal.core.Action only(java.util.List<Action> actions) {
        return CrystalBrainParityTest.only(actions);
    }

    @Test
    void aLateCrystalOfAKillPlacementIsJudgedByTheFloorAndNeverByTheOverride() {
        CrystalSettings noFast = LOW_MIN.toBuilder().fastBreak(false).build();
        // Health 6.5: self 5 leaves 1.5 (< 2): nothing, from either path, and never a finishing blow.
        CrystalBrain over = new CrystalBrain();
        long t = killPlacementExpired(over, LOW_MIN);
        assertTrue(over.crystalAdded(LOW_MIN, crystal(960, 3000L, 6.0, 5), 6.5, TOTEM, 2).isEmpty());
        assertEquals(1, over.lateOwnCrystals());
        assertNothing(over.preTick(LOW_MIN, tick(t).health(6.5).hands(TOTEM).targets(KILL)
            .crystals(crystal(960, 3000L, 6.0, 5)).build()));

        CrystalBrain overPre = new CrystalBrain();
        long tp = killPlacementExpired(overPre, noFast);
        assertNothing(overPre.preTick(noFast, tick(tp).health(6.5).hands(TOTEM).targets(KILL)
            .crystals(crystal(960, 3000L, 6.0, 5)).build()));

        // Self 4.5 leaves exactly 2: broken by the ordinary budget, WITHIN_BUDGET.
        CrystalBrain within = new CrystalBrain();
        killPlacementExpired(within, LOW_MIN);
        assertEquals(Decision.breakCrystal(960, Reason.WITHIN_BUDGET),
            within.crystalAdded(LOW_MIN, crystal(960, 3000L, 6.0, 4.5), 6.5, TOTEM, 2).orElseThrow().decision());

        CrystalBrain withinPre = new CrystalBrain();
        long tw = killPlacementExpired(withinPre, noFast);
        assertEquals(Decision.breakCrystal(960, Reason.WITHIN_BUDGET), only(withinPre.preTick(noFast, tick(tw).health(6.5)
            .hands(TOTEM).targets(KILL).crystals(crystal(960, 3000L, 6.0, 4.5)).build())).decision());
    }

    // The budget off: the finishing blow never acts, Meteor's rules only

    @Test
    void withTheBudgetOffTheFinishingBlowNeverActsAndMeteorsRefusalsStand() {
        CrystalSettings off = Crystals.METEOR.toBuilder().minDamage(1).build();
        assertTrue(off.finishingBlow() && !off.selfBudget());
        java.util.Map<String, Double> six = java.util.Map.of(Crystals.ENEMY, 6.0);
        // Trust was learned while the budget was on (sticky), then the player turned it off: the blow is finishing-grade
        // (6 >= 1.25 * 4) with a totem in hand and a spare, health just above pause-health (5).
        for (boolean fast : new boolean[] {true, false}) {
            CrystalSettings s = off.toBuilder().fastBreak(fast).build();
            CrystalBrain b = new CrystalBrain();
            long t = CrystalBrainFinishingBlowTest.trustedEnemy(b, 1);
            assertEquals(Decision.place(3000L, Reason.BUDGET_OFF), only(b.preTick(s, tick(t).health(5.25).hands(TOTEM)
                .targets(KILL).candidates(spot(3000L, six, 1)).build())).decision());
            b.placed(3000L, 0);
            assertNothing(b.preTick(s, tick(t + 1).health(5.25).hands(TOTEM).targets(KILL).build()));
            var added = b.crystalAdded(s, crystal(960, 3000L, 6.0, 1), 5.25, TOTEM, 2);
            if (fast) {
                assertEquals(Decision.breakCrystal(960, Reason.BUDGET_OFF), added.orElseThrow().decision());
            } else {
                assertTrue(added.isEmpty());
                assertEquals(Decision.breakCrystal(960, Reason.BUDGET_OFF), only(b.preTick(s, tick(t + 2).health(5.25)
                    .hands(TOTEM).targets(KILL).crystals(crystal(960, 3000L, 6.0, 1)).build())).decision());
            }
        }
        // At pause-health (5) Meteor pauses, and with the budget off no finishing blow gives way to it.
        CrystalBrain paused = new CrystalBrain();
        long tq = CrystalBrainFinishingBlowTest.trustedEnemy(paused, 1);
        assertNothing(paused.preTick(off, tick(tq).health(5).hands(TOTEM).targets(KILL)
            .candidates(spot(3000L, six, 1)).crystals(crystal(962, 3002L, 6.0, 1)).build()));
        // What Meteor refuses stays refused: past max-damage (6.5 > 6), and anti-suicide (5.25 >= 5.25) at self 5.25... 
        // 5.25 is below max-damage 6, so only anti-suicide refuses it.
        for (double self : new double[] {6.5, 5.25}) {
            CrystalBrain b = new CrystalBrain();
            long t = CrystalBrainFinishingBlowTest.trustedEnemy(b, 1);
            assertNothing(b.preTick(off, tick(t).health(5.25).hands(TOTEM).targets(KILL)
                .candidates(spot(3000L, six, self)).build()));
            CrystalBrain c = new CrystalBrain();
            long u = CrystalBrainFinishingBlowTest.trustedEnemy(c, 1);
            // One of OURS (placed in time at a spot Meteor accepted), measured on arrival at a self damage it refuses.
            assertPlaces(3001L, c.preTick(off, tick(u).health(20).hands(TOTEM).targets(KILL)
                .candidates(spot(3001L, six, 1)).build()));
            c.placed(3001L, 0);
            assertNothing(c.preTick(off, tick(u + 1).health(5.25).hands(TOTEM).targets(KILL).build()));
            u += 2;
            assertTrue(c.crystalAdded(off, crystal(961, 3001L, 6.0, self), 5.25, TOTEM, 2).isEmpty());
            assertNothing(c.preTick(off, tick(u).health(5.25).hands(TOTEM).targets(KILL)
                .crystals(crystal(961, 3001L, 6.0, self)).build()));
        }
    }
}
