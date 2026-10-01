package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertBreaks;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rest of a placement burst stays ours (0.8.0). Like Meteor, the brain sends its placement on the same base
 * every pre-tick until a crystal appears there, and the first crystal settles the pending placement. If that first
 * crystal is destroyed while a later packet of the same burst is still on its way, that packet makes a second crystal
 * of ours with no pending placement left. Before this fix the brain took it for someone else's crystal and broke it
 * by Meteor's rules alone, with neither the reserve nor the floor (seen on the bench, predicted to leave 0.2 to 0.5
 * health). Now, for {@link CrystalBrain#LATE_OWN_WINDOW} pre-ticks after the burst's last packet, a crystal on that
 * base is one of ours for the budget, the same way a late own crystal is: Meteor's rules still bound it, and breaking
 * it must also leave the floor.
 */
class CrystalBrainBurstTailTest {
    private static final CrystalSettings NO_FAST = DEFAULTS.toBuilder().fastBreak(false).build();

    /**
     * A burst on base 9 (self {@code self}), sent at pre-ticks 1 and 2, so its last packet is at 2. Its first
     * crystal (id 1) appears after pre-tick 2 and is not broken by us (fast-break off for it); another explosion
     * takes it away before pre-tick 3, where it is no longer listed. Then empty pre-ticks at full health up to
     * {@code last}. From pre-tick 6 on, the first crystal has left the disappearance window: I = 0.
     */
    private static CrystalBrain burst(CrystalSettings settings, double self, long last) {
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(settings, tick(1).candidates(spot(9, 8, self)).build()));
        b.placed(9, 0);
        assertPlaces(9, b.preTick(settings, tick(2).candidates(spot(9, 8, self)).build()));
        b.placed(9, 0);
        CrystalSettings noFast = settings.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFast, crystal(1, 9, 8, self), 20, HANDS).isEmpty());
        assertEquals(Map.of(1, false), b.ownCrystalsWithBurstTail(), "the first crystal of the burst is ours");
        assertEquals(Map.of(1, false), b.ownCrystalsWithoutBurstTail(), "and the aura looks after it itself");
        for (long t = 3; t <= last; t++) b.preTick(settings, tick(t).build());
        return b;
    }

    // The second crystal of the burst is ours for the budget

    @Test
    void aSecondCrystalOfTheBurstPastTheFloorIsNotFastBroken() {
        // 6.5 - 0 - 5 = 1.5 < 2. As a foreign crystal (before the fix) Meteor's rules alone broke it: 5 <= 6 (max-damage)
        // and 5 < 6.5 (anti-suicide).
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(Map.of(2, false), b.ownCrystalsWithBurstTail());
        assertEquals(0, b.lateOwnCrystals(), "it is not late: its burst's first crystal came in time");
    }

    @Test
    void aSecondCrystalOfTheBurstWithinTheFloorIsBrokenAsOurs() {
        // 7 - 0 - 5 = 2 >= 2 (exactly the floor): broken, and as ours (WITHIN_BUDGET), no longer FOREIGN_CRYSTAL.
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertEquals(Decision.breakCrystal(2, Reason.WITHIN_BUDGET),
            b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 7, HANDS).orElseThrow().decision());
    }

    @Test
    void thePreTickBreakReadsTheSameFloor() {
        for (double health : new double[] {6.5, 7}) {
            CrystalBrain b = burst(NO_FAST, 5, 6);
            assertTrue(b.crystalAdded(NO_FAST, crystal(2, 9, 8, 5), 20, HANDS).isEmpty());
            List<Action> actions = b.preTick(NO_FAST, tick(7).health(health).crystals(crystal(2, 9, 8, 5)).build());
            if (health >= 7) assertBreaks(2, actions);
            else {
                assertNothing(actions);
                assertTrue(b.holding());
                assertEquals(Reason.BELOW_FLOOR, b.lastDecision().reason());
            }
        }
    }

    @Test
    void whatIsAlreadyInFlightCountsAgainstIt() {
        // Another crystal of ours (self 4) attacked at pre-tick 8 is still in Meteor's wait at 9: I = 4.
        // 10 - 4 - 5 = 1 < 2: not broken. 11 - 4 - 5 = 2: broken.
        for (double health : new double[] {10, 11}) {
            CrystalBrain b = burst(DEFAULTS, 5, 6);
            assertPlaces(20, b.preTick(DEFAULTS, tick(7).candidates(spot(20, 8, 4)).build()));
            b.placed(20, 0);
            // No fast-break in the pre-tick that rotated to place: the next pre-tick breaks it.
            assertTrue(b.crystalAdded(DEFAULTS, crystal(3, 20, 8, 4), 20, HANDS).isEmpty());
            assertBreaks(3, b.preTick(DEFAULTS, tick(8).crystals(crystal(3, 20, 8, 4)).build()));
            b.attackSent();
            b.preTick(DEFAULTS, tick(9).crystals(crystal(3, 20, 8, 4)).build());
            assertEquals(health >= 11, b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), health, HANDS).isPresent(),
                "health " + health);
        }
    }

    @Test
    void meteorsMaxDamageStillBoundsIt() {
        // Ours for the budget, but not ours for Meteor's rules: past max-damage (6.5 > 6) it is not broken even at 20
        // health, as a late own crystal (the reserve replaces max-damage only for a crystal of a pending placement).
        CrystalBrain b = burst(DEFAULTS, 6.5, 6);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 6.5), 20, HANDS).isEmpty());
        assertNothing(b.preTick(DEFAULTS, tick(7).crystals(crystal(2, 9, 8, 6.5)).build()));
        // The burst's first crystal, one of a pending placement, is broken at the same self damage (20 - 6.5 >= 2).
        CrystalBrain first = new CrystalBrain();
        assertPlaces(9, first.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 6.5)).build()));
        first.placed(9, 0);
        assertTrue(first.crystalAdded(DEFAULTS, crystal(1, 9, 8, 6.5), 20, HANDS).isEmpty());
        assertEquals(Decision.breakCrystal(1, Reason.WITHIN_BUDGET),
            CrystalBrainParityTest.only(first.preTick(DEFAULTS, tick(2).crystals(crystal(1, 9, 8, 6.5)).build())).decision());
    }

    @Test
    void itStandsInTheBudgetLikeAnyStandingCrystal() {
        // Standing out of break range, self 3; health 9: a second spot of self 3 sees 9 - 3 - 3 = 3 < 3.5 and is
        // refused; with no crystal standing it goes (9 - 3 = 6).
        CrystalSeen standing = Crystals.outOfBreakRange(crystal(2, 9, 8, 3));
        CrystalBrain b = burst(DEFAULTS, 3, 6);
        assertTrue(b.crystalAdded(DEFAULTS, standing, 9, HANDS).isEmpty());
        assertEquals(Map.of(2, false), b.ownCrystalsWithBurstTail());
        assertNothing(b.preTick(DEFAULTS, tick(7).health(9).crystals(standing).candidates(spot(10, 8, 3)).build()));
        assertEquals(Reason.OVER_RESERVE, b.lastDecision().reason());
        assertPlaces(10, burst(DEFAULTS, 3, 6).preTick(DEFAULTS, tick(7).health(9).candidates(spot(10, 8, 3)).build()));
    }

    // How long the burst's tail lasts

    @Test
    void theTailLastsFromTheBurstsLastPacket() {
        // Last packet at pre-tick 2: a crystal first seen at pre-tick 21 (19 later) is still ours; at 22 (20 later) the
        // window is over and it is foreign again, broken by Meteor's rules alone.
        long end = 2 + CrystalBrain.LATE_OWN_WINDOW;
        CrystalBrain still = burst(DEFAULTS, 5, end - 1);
        assertTrue(still.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS).isEmpty());
        CrystalBrain over = burst(DEFAULTS, 5, end);
        assertEquals(Decision.breakCrystal(2, Reason.FOREIGN_CRYSTAL),
            over.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS).orElseThrow().decision());
        assertTrue(over.ownCrystalsWithBurstTail().isEmpty());
    }

    @Test
    void theTailIsSeenAtAPreTickToo() {
        // The same boundary when the crystal is first seen in a pre-tick's list rather than on its arrival.
        long end = 2 + CrystalBrain.LATE_OWN_WINDOW;
        CrystalBrain still = burst(NO_FAST, 5, end - 1);
        assertNothing(still.preTick(NO_FAST, tick(end).health(6.5).crystals(crystal(2, 9, 8, 5)).build()));
        CrystalBrain over = burst(NO_FAST, 5, end);
        assertBreaks(2, over.preTick(NO_FAST, tick(end + 1).health(6.5).crystals(crystal(2, 9, 8, 5)).build()));
    }

    @Test
    void everyCrystalOfTheBurstInTheWindowIsOurs() {
        // The second crystal (self 5, standing unbroken at 6.5) is taken away by another explosion at pre-tick 8; a
        // third one of the same burst appears after 12 (the second has left the window by 11): still ours, still the floor.
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS).isEmpty());
        b.preTick(DEFAULTS, tick(7).health(6.5).crystals(crystal(2, 9, 8, 5)).build());
        for (long t = 8; t <= 12; t++) b.preTick(DEFAULTS, tick(t).health(6.5).build());
        assertTrue(b.crystalAdded(DEFAULTS, crystal(3, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(Map.of(3, false), b.ownCrystalsWithBurstTail());
    }

    @Test
    void aNewPlacementOnThatBaseIsItsOwnPendingOne() {
        // Placing on base 9 again (self 6.5) makes the next crystal there one of a pending placement: the reserve
        // replaces max-damage for it (20 - 6.5 >= 2), so it is broken, where a crystal of the old burst's tail is not.
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertPlaces(9, b.preTick(DEFAULTS, tick(7).candidates(spot(9, 8, 6.5)).build()));
        b.placed(9, 0);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 6.5), 20, HANDS).isEmpty());
        assertEquals(Decision.breakCrystal(2, Reason.WITHIN_BUDGET),
            CrystalBrainParityTest.only(b.preTick(DEFAULTS, tick(8).crystals(crystal(2, 9, 8, 6.5)).build())).decision());
    }

    @Test
    void anotherBaseIsNotPartOfTheBurst() {
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertEquals(Decision.breakCrystal(2, Reason.FOREIGN_CRYSTAL),
            b.crystalAdded(DEFAULTS, crystal(2, 10, 8, 5), 6.5, HANDS).orElseThrow().decision());
    }

    @Test
    void theRestOfALateBurstStaysOursToo() {
        // The burst's pending placement (last packet at 1) expires at pre-tick 6 and its crystal lands late (a late own
        // crystal, left standing); another explosion takes it away at 8; a second crystal of that burst after 12 is
        // still one of ours: the floor refuses it at 6.5 (6.5 - 5 = 1.5 < 2).
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        for (long t = 2; t <= 6; t++) b.preTick(DEFAULTS, tick(t).build());
        assertTrue(b.crystalAdded(DEFAULTS, crystal(1, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(1, b.lateOwnCrystals());
        b.preTick(DEFAULTS, tick(7).health(6.5).crystals(crystal(1, 9, 8, 5)).build());
        for (long t = 8; t <= 12; t++) b.preTick(DEFAULTS, tick(t).health(6.5).build());
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(1, b.lateOwnCrystals(), "the second crystal of a late burst is not a second late placement");
        assertEquals(Map.of(2, false), b.ownCrystalsWithBurstTail());
    }

    // What surround++ is told (fix round 1, review I1): only what the aura looks after itself

    @Test
    void aTailCrystalIsOursForTheBenchButNotOneTheShellLeavesToTheAura() {
        // Known only through the tail, it may as well be an opponent's crystal on a base we just used: surround++
        // (which skips every crystal the aura looks after) must still be free to break it, as before the tail.
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(Map.of(2, false), b.ownCrystalsWithBurstTail());
        assertTrue(b.ownCrystalsWithoutBurstTail().isEmpty());
    }

    @Test
    void pendingAndLateCrystalsAreInBothViewsAsBefore() {
        // A crystal of a pending placement (the burst fixture checks it too) and a late own crystal: both views.
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        for (long t = 2; t <= 6; t++) b.preTick(DEFAULTS, tick(t).build());
        assertTrue(b.crystalAdded(DEFAULTS, crystal(1, 9, 8, 5), 6.5, HANDS).isEmpty());
        assertEquals(1, b.lateOwnCrystals());
        assertEquals(Map.of(1, false), b.ownCrystalsWithBurstTail());
        assertEquals(Map.of(1, false), b.ownCrystalsWithoutBurstTail());
    }

    // A late crystal's tail runs from its arrival (fix round 1, review M6)

    @Test
    void theTailOfALateBurstRunsFromTheLateCrystalsArrival() {
        // Last packet at 1; the placement expires at 6 and its crystal lands late, first seen at pre-tick 20 (left
        // standing: 6.5 - 5 < 2). Another explosion takes it away at 21. From the last packet the tail would have ended
        // at 21; from the arrival it runs to 40: a second crystal of the burst first seen at 39 is ours (the floor
        // refuses it), one first seen at 40 is foreign again (broken by Meteor's rules alone).
        for (long seen : new long[] {39, 40}) {
            CrystalBrain b = new CrystalBrain();
            assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
            b.placed(9, 0);
            for (long t = 2; t <= 20; t++) b.preTick(DEFAULTS, tick(t).build());
            assertTrue(b.crystalAdded(DEFAULTS, crystal(1, 9, 8, 5), 6.5, HANDS).isEmpty());
            assertEquals(1, b.lateOwnCrystals());
            for (long t = 21; t <= seen; t++) b.preTick(DEFAULTS, tick(t).health(6.5).build());
            var action = b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.5, HANDS);
            if (seen < 40) assertTrue(action.isEmpty(), "first seen at " + seen);
            else assertEquals(Decision.breakCrystal(2, Reason.FOREIGN_CRYSTAL), action.orElseThrow().decision());
        }
    }

    // A tail crystal the floor will not break holds every placement back too (fix round 1, review M7)

    @Test
    void aTailCrystalTheFloorRefusesHoldsEveryPlacementBack() {
        // Self 5 at health 6.9: 6.9 - 5 = 1.9 < 2, not broken. A spot elsewhere of self 0.4: 6.9 - 5 - 0.4 = 1.5, under
        // the reserve and, although 0.4 is within safe-self-damage, under the floor: refused. Nothing, BELOW_FLOOR.
        CrystalBrain b = burst(DEFAULTS, 5, 6);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(2, 9, 8, 5), 6.9, HANDS).isEmpty());
        assertNothing(b.preTick(DEFAULTS, tick(7).health(6.9).crystals(crystal(2, 9, 8, 5))
            .candidates(spot(10, 8, 0.4)).build()));
        assertTrue(b.holding());
        assertEquals(Reason.BELOW_FLOOR, b.lastDecision().reason());
        // Control: with nothing standing the same spot goes (6.9 - 0.4 = 6.5 >= 3.5).
        assertPlaces(10, burst(DEFAULTS, 5, 6).preTick(DEFAULTS, tick(7).health(6.9).candidates(spot(10, 8, 0.4)).build()));
    }

    // The budget off: Meteor's rules only, exactly as before

    @Test
    void withTheBudgetOffTheSecondCrystalIsJudgedLikeAnyOther() {
        for (boolean fast : new boolean[] {true, false}) {
            CrystalSettings off = METEOR.toBuilder().fastBreak(fast).build();
            CrystalBrain b = burst(off, 5, 6);
            CrystalBrain control = new CrystalBrain();
            for (long t = 1; t <= 6; t++) control.preTick(off, tick(t).build());
            var fromBurst = b.crystalAdded(off, crystal(2, 9, 8, 5), 6.5, HANDS);
            var fromControl = control.crystalAdded(off, crystal(2, 9, 8, 5), 6.5, HANDS);
            assertEquals(fromControl.map(Action::decision), fromBurst.map(Action::decision));
            if (fast) {
                assertEquals(Decision.breakCrystal(2, Reason.BUDGET_OFF), fromBurst.orElseThrow().decision());
            } else {
                List<Action> burstTick = b.preTick(off, tick(7).health(6.5).crystals(crystal(2, 9, 8, 5)).build());
                List<Action> controlTick = control.preTick(off, tick(7).health(6.5).crystals(crystal(2, 9, 8, 5)).build());
                assertEquals(controlTick, burstTick);
                assertBreaks(2, burstTick);
            }
        }
    }
}
