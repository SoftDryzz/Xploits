package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertBreaks;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.at;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.dealing;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget inside the brain (spec §1, P2-P4, Q2, Q3): what it refuses that Meteor would do, what it
 * never touches, whose crystal is whose, the next-best fallback and {@code holding()}. Every number is
 * exact in binary.
 */
class CrystalBrainBudgetTest {
    /**
     * Makes these crystals ours the way it happens in game: at full health each is placed in turn and then
     * appears while its placement is pending. Until the test measures them again they deal nothing, so
     * nothing breaks them and they do not stop the next placement.
     *
     * @return the next free pre-tick
     */
    private static long own(CrystalBrain b, long t, CrystalSeen... crystals) {
        List<CrystalSeen> standing = new ArrayList<>();
        for (CrystalSeen c : crystals) {
            assertPlaces(c.pos(), b.preTick(DEFAULTS, tick(t).crystals(standing).candidates(spot(c.pos(), 8, c.selfDamage())).build()));
            b.placed(c.pos(), 0);
            CrystalSeen harmless = dealing(c, 0);
            assertTrue(b.crystalAdded(harmless, 20, HANDS).isEmpty());
            standing.add(harmless);
            t++;
        }
        return t;
    }

    private static void assertDecision(Decision expected, List<Action> actions) {
        assertEquals(expected, only(actions).decision());
    }

    // What the budget refuses that Meteor would do

    @Test
    void aSecondBreakTheNextTickWithStaleHealthIsRefusedBecauseTheFirstIsInFlight() {
        // Meteor checks each crystal alone (5 < 11, twice); health has not dropped yet when it decides again.
        CrystalSeen a = crystal(1, 8, 5);
        CrystalSeen c = crystal(2, 7.5, 5);
        CrystalBrain b = new CrystalBrain();
        long t = own(b, 1, a, c);

        // 11 - 0 - 5 = 6 >= 2
        assertDecision(Decision.breakCrystal(1, Reason.WITHIN_BUDGET), b.preTick(DEFAULTS, tick(t).health(11).crystals(a, c).build()));
        b.attackSent();
        // Still 11, and a is in I: 11 - 5 - 5 = 1 < 2
        assertNothing(b.preTick(DEFAULTS, tick(t + 1).health(11).crystals(a, c).build()));
        assertTrue(b.holding());
        assertEquals(Decision.none(Reason.BELOW_FLOOR), b.lastDecision());

        CrystalBrain meteor = new CrystalBrain();
        assertBreaks(1, meteor.preTick(METEOR, tick(1).health(11).crystals(a, c).build()));
        assertBreaks(2, meteor.preTick(METEOR, tick(2).health(11).crystals(a, c).build()));
    }

    @Test
    void anExplodedCrystalStaysInFlightForThreeTicks() {
        // a is gone at t + 1 (seen missing); its damage may not have arrived: I holds it at t + 1, t + 2 and t + 3.
        CrystalSeen a = crystal(1, 8, 5);
        CrystalSeen c = crystal(2, 7.5, 5);
        CrystalBrain b = new CrystalBrain();
        long t = own(b, 1, a, c);
        assertDecision(Decision.breakCrystal(1, Reason.WITHIN_BUDGET), b.preTick(DEFAULTS, tick(t).health(11).crystals(a, c).build()));
        b.attackSent();

        for (long n = t + 1; n <= t + 3; n++) {
            assertNothing(b.preTick(DEFAULTS, tick(n).health(11).crystals(c).build()));
        }
        // 11 - 0 - 5 = 6
        assertDecision(Decision.breakCrystal(2, Reason.WITHIN_BUDGET), b.preTick(DEFAULTS, tick(t + 4).health(11).crystals(c).build()));
    }

    @Test
    void aCrystalThatVanishesWithoutOurAttackCountsInFlightAtOnce() {
        // A foreign crystal of self 4 leaves the world between pre-ticks; a crystal of ours appears right after.
        // 8 - 4 - 4 = 0 < 2: fast-break waits. Standing, it would only have been in S: 8 - 0 - 4 = 4.
        CrystalSeen theirs = crystal(1, 0, 4);
        CrystalSeen mine = crystal(2, 5000, 8, 4);
        CrystalBrain b = new CrystalBrain();
        assertPlaces(5000, b.preTick(DEFAULTS, tick(1).crystals(theirs).candidates(spot(5000, 8, 4)).build()));
        b.placed(5000, 0);
        assertNothing(b.preTick(DEFAULTS, tick(2).health(8).crystals(theirs).build()));

        b.crystalRemoved(1);
        assertTrue(b.crystalAdded(mine, 8, HANDS).isEmpty());

        CrystalBrain standing = new CrystalBrain();
        assertPlaces(5000, standing.preTick(DEFAULTS, tick(1).crystals(theirs).candidates(spot(5000, 8, 4)).build()));
        standing.placed(5000, 0);
        assertNothing(standing.preTick(DEFAULTS, tick(2).health(8).crystals(theirs).build()));
        assertDecision(Decision.breakCrystal(2, Reason.WITHIN_BUDGET), List.of(standing.crystalAdded(mine, 8, HANDS).orElseThrow()));
    }

    @Test
    void aCrystalTheBudgetWillNotBreakStillBlocksPlacement() {
        // Ours, self 4, at health 5.5: 5.5 - 0 - 4 = 1.5 < 2, so it stays. Meteor would break it (4 < 5.5), so
        // by Meteor's rule it stops placing, and the budget does not change that. Here it is measured beyond
        // the hazard radius so S does not hold it and the budget alone would let the spot through
        // (5.5 - 0 - 0 = 5.5 >= 5): only the one-at-a-time rule says no.
        CrystalSeen mine = crystal(1, 8, 4);
        CrystalBrain b = new CrystalBrain();
        long t = own(b, 1, mine);

        assertNothing(b.preTick(DEFAULTS, tick(t).health(5.5).crystals(at(mine, 12.5)).candidates(spot(9, 8, 0)).build()));
        assertTrue(b.holding());
        assertEquals(Decision.none(Reason.BELOW_FLOOR), b.lastDecision());
    }

    @Test
    void aCrystalMeteorWouldBreakBlocksPlacementEvenWhenTheBudgetHasRoom() {
        // Breaking is paused; the foreign crystal counts in S, and 20 - 1 - 1 = 18 would pass the reserve.
        CrystalSettings s = DEFAULTS.toBuilder().pauseOnUse(PauseMode.BREAK).build();

        assertNothing(new CrystalBrain().preTick(s, tick(1).usingItem().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1)).build()));
    }

    @Test
    void ourOwnCrystalCanBeBrokenDownToTheFloor() {
        CrystalSeen mine = crystal(1, 8, 4);

        CrystalBrain atFloor = new CrystalBrain();
        long t = own(atFloor, 1, mine);
        // 6 - 0 - 4 = 2
        assertDecision(Decision.breakCrystal(1, Reason.WITHIN_BUDGET), atFloor.preTick(DEFAULTS, tick(t).health(6).crystals(mine).build()));
        assertFalse(atFloor.holding());

        CrystalBrain below = new CrystalBrain();
        t = own(below, 1, mine);
        // 5.75 - 0 - 4 = 1.75
        assertNothing(below.preTick(DEFAULTS, tick(t).health(5.75).crystals(mine).build()));
        assertTrue(below.holding());
    }

    @Test
    void aForeignCrystalIsBrokenUnderMeteorsRulesOnly() {
        // Health 5.25 with a crystal of ours in flight: ours would leave 5.25 - 1 - 5 < 2. Meteor breaks the
        // foreign one (5 <= 6, 5 < 5.25), and so does ++, periodic and fast.
        CrystalSeen mine = crystal(2, 8, 1);
        CrystalBrain b = new CrystalBrain();
        long t = own(b, 1, mine);
        assertDecision(Decision.breakCrystal(2, Reason.WITHIN_BUDGET), b.preTick(DEFAULTS, tick(t).health(20).crystals(mine).build()));
        b.attackSent();

        assertDecision(Decision.breakCrystal(1, Reason.FOREIGN_CRYSTAL),
            b.preTick(DEFAULTS, tick(t + 1).health(5.25).crystals(mine, crystal(1, 8, 5)).build()));
        // Meteor's own checks still hold: anti-suicide, and pause-health.
        assertNothing(new CrystalBrain().preTick(DEFAULTS, tick(1).health(5.25).crystals(crystal(1, 8, 5.25)).build()));
        assertNothing(new CrystalBrain().preTick(DEFAULTS, tick(1).health(5).crystals(crystal(1, 8, 1)).build()));

        CrystalBrain fast = new CrystalBrain();
        fast.preTick(DEFAULTS, tick(1).health(5.25).build());
        assertEquals(Decision.breakCrystal(1, Reason.FOREIGN_CRYSTAL),
            fast.crystalAdded(crystal(1, 8, 5), 5.25, HANDS).orElseThrow().decision());
    }

    @Test
    void fastBreakReadsHealthAtThatMoment() {
        // P4: our crystal appears; 6.5 - 0 - 5 = 1.5 < 2 now, even though the pre-tick said 20.
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        assertNothing(b.preTick(DEFAULTS, tick(2).build()));

        assertTrue(b.crystalAdded(crystal(1, 9, 8, 5), 6.5, HANDS).isEmpty());

        CrystalBrain enough = new CrystalBrain();
        assertPlaces(9, enough.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        enough.placed(9, 0);
        assertNothing(enough.preTick(DEFAULTS, tick(2).health(5.25).build()));
        // 7 - 0 - 5 = 2
        assertEquals(Decision.breakCrystal(1, Reason.WITHIN_BUDGET),
            enough.crystalAdded(crystal(1, 9, 8, 5), 7, HANDS).orElseThrow().decision());
    }

    // Pending placements and ownership (Q2)

    /** Places on base 9 at tick 1 (self 5), then runs empty pre-ticks at health 6.5 up to {@code last}. */
    private static CrystalBrain placedAtTick1(int pingTicks, long last) {
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        b.placed(9, pingTicks);
        for (long t = 2; t <= last; t++) b.preTick(DEFAULTS, tick(t).health(6.5).build());
        return b;
    }

    @Test
    void aCrystalWithinFiveTicksOfOurPlacementIsOursLaterItIsForeign() {
        // Ping 0: max(5, 0 + 2) = 5, Meteor's placing window (lines 667-669, 734-738, 1055). The crystal
        // (self 5) at health 6.5: ours cannot be broken (1.5 < 2), a foreign one is.
        CrystalSeen there = crystal(1, 9, 8, 5);

        CrystalBrain inTime = placedAtTick1(0, 5);
        assertTrue(inTime.crystalAdded(there, 6.5, HANDS).isEmpty());
        assertEquals(0, inTime.lateOwnCrystals());

        CrystalBrain late = placedAtTick1(0, 6);
        assertEquals(Decision.breakCrystal(1, Reason.FOREIGN_CRYSTAL), late.crystalAdded(there, 6.5, HANDS).orElseThrow().decision());
        assertEquals(1, late.lateOwnCrystals());
    }

    @Test
    void aCrystalFirstSeenAtAPreTickAppearedBeforeIt() {
        // Seen at pre-tick 6 without an EntityAdded: it appeared before 6, while the placement was pending.
        CrystalSeen there = crystal(1, 9, 8, 5);

        CrystalBrain inTime = placedAtTick1(0, 5);
        assertNothing(inTime.preTick(DEFAULTS, tick(6).health(6.5).crystals(there).build()));
        assertTrue(inTime.holding());
        assertEquals(0, inTime.lateOwnCrystals());

        CrystalBrain late = placedAtTick1(0, 6);
        assertDecision(Decision.breakCrystal(1, Reason.FOREIGN_CRYSTAL), late.preTick(DEFAULTS, tick(7).health(6.5).crystals(there).build()));
        assertEquals(1, late.lateOwnCrystals());
    }

    @Test
    void thePendingLifetimeFollowsThePing() {
        // max(5, ping + 2); an unknown ping counts as 5 ticks (Q6), so 7.
        CrystalSeen there = crystal(1, 9, 8, 5);
        assertEquals(5, CrystalBrain.UNKNOWN_PING_TICKS);

        assertTrue(placedAtTick1(CrystalBrain.UNKNOWN_PING_TICKS, 7).crystalAdded(there, 6.5, HANDS).isEmpty());
        CrystalBrain late = placedAtTick1(CrystalBrain.UNKNOWN_PING_TICKS, 8);
        assertTrue(late.crystalAdded(there, 6.5, HANDS).isPresent());
        assertEquals(1, late.lateOwnCrystals());

        assertTrue(placedAtTick1(3, 5).crystalAdded(there, 6.5, HANDS).isEmpty());
        assertTrue(placedAtTick1(3, 6).crystalAdded(there, 6.5, HANDS).isPresent());
        assertTrue(placedAtTick1(10, 12).crystalAdded(there, 6.5, HANDS).isEmpty());
        assertTrue(placedAtTick1(10, 13).crystalAdded(there, 6.5, HANDS).isPresent());
    }

    @Test
    void aSpotIsForgottenTwentyTicksAfterItsPlacementExpired() {
        // Expired at pre-tick 6; a crystal there by pre-tick 25 is a late own crystal, one after that is
        // simply someone else's.
        CrystalSeen there = crystal(1, 9, 8, 5);
        assertEquals(20, CrystalBrain.LATE_OWN_WINDOW);

        CrystalBrain stillLate = placedAtTick1(0, 25);
        assertTrue(stillLate.crystalAdded(there, 6.5, HANDS).isPresent());
        assertEquals(1, stillLate.lateOwnCrystals());

        CrystalBrain forgotten = placedAtTick1(0, 26);
        assertTrue(forgotten.crystalAdded(there, 6.5, HANDS).isPresent());
        assertEquals(0, forgotten.lateOwnCrystals());
    }

    @Test
    void aPendingPlacementCountsUntilItsCrystalAppearsAndThenOnlyTheCrystalCounts() {
        // Meteor would place self 5 at health 12 (5 < 12); the pending placement is in S: 12 - 5 - 5 = 2 < 5.
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        assertNothing(b.preTick(DEFAULTS, tick(2).health(12).candidates(spot(8, 8, 5)).build()));
        assertEquals(Decision.none(Reason.OVER_RESERVE), b.lastDecision());

        // It appears (dealing nothing, so it stops no placement): only the crystal counts, 15 - 5 - 5 = 5.
        CrystalSeen appeared = crystal(1, 9, 0, 5);
        assertTrue(b.crystalAdded(appeared, 15, HANDS).isEmpty());
        assertPlaces(8, b.preTick(DEFAULTS, tick(3).health(15).crystals(appeared).candidates(spot(8, 8, 5)).build()));

        // Expired with no crystal, it no longer counts: refused at pre-tick 5, placed at 6 (12 - 0 - 5 = 7).
        CrystalBrain lost = new CrystalBrain();
        assertPlaces(9, lost.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        lost.placed(9, 0);
        for (long t = 2; t <= 5; t++) assertNothing(lost.preTick(DEFAULTS, tick(t).health(12).candidates(spot(8, 8, 5)).build()));
        assertPlaces(8, lost.preTick(DEFAULTS, tick(6).health(12).candidates(spot(8, 8, 5)).build()));
    }

    /** Places on base 9 (self 5) at pre-ticks 1 and 2 at health 15, as Meteor does until the crystal arrives. */
    private static CrystalBrain placedTwice() {
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).health(15).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        // 15 - 0 - 5 = 10: the placement pending there does not count against the same spot
        assertPlaces(9, b.preTick(DEFAULTS, tick(2).health(15).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        return b;
    }

    @Test
    void aCrystalPlacedTwiceAtOneSpotCountsOnce() {
        // The crystal appears (dealing nothing, so it stops no placement). Only it counts in S:
        // 15 - 5 - 5 = 5 >= 5, as after a single placement. Two pending entries would leave one behind:
        // 15 - 10 - 5 = 0.
        CrystalSeen appeared = crystal(1, 9, 0, 5);

        CrystalBrain twice = placedTwice();
        assertTrue(twice.crystalAdded(appeared, 15, HANDS).isEmpty());
        assertPlaces(8, twice.preTick(DEFAULTS, tick(3).health(15).crystals(appeared).candidates(spot(8, 8, 5)).build()));

        CrystalBrain once = new CrystalBrain();
        assertPlaces(9, once.preTick(DEFAULTS, tick(1).health(15).candidates(spot(9, 8, 5)).build()));
        once.placed(9, 0);
        assertNothing(once.preTick(DEFAULTS, tick(2).health(15).build()));
        assertTrue(once.crystalAdded(appeared, 15, HANDS).isEmpty());
        assertPlaces(8, once.preTick(DEFAULTS, tick(3).health(15).crystals(appeared).candidates(spot(8, 8, 5)).build()));
    }

    @Test
    void aSpotPlacedTwiceLeavesNothingToCountAsLateOnceItsCrystalCame() {
        // Our crystal arrives after pre-tick 2, stands until 8 and goes; someone else's lands there after 9.
        // Had the first placement stayed pending, it would have expired into a late spot and been counted.
        CrystalSeen ours = crystal(1, 9, 0, 5);
        CrystalBrain b = placedTwice();
        assertTrue(b.crystalAdded(ours, 15, HANDS).isEmpty());
        for (long t = 3; t <= 8; t++) b.preTick(DEFAULTS, tick(t).health(15).crystals(ours).build());
        b.preTick(DEFAULTS, tick(9).health(15).build());

        assertTrue(b.crystalAdded(crystal(2, 9, 8, 5), 15, HANDS).isPresent());
        assertEquals(0, b.lateOwnCrystals());
    }

    @Test
    void aSpotStillPendingIsNotCountedAgainstItself() {
        // Health 12 with self 5 pending on 9: spot 8 (the best) would leave 12 - 5 - 5 = 2 < 5; placing on 9
        // again replaces the pending one, 12 - 0 - 5 = 7.
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).health(12).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);

        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(DEFAULTS, tick(2).health(12).candidates(spot(8, 10, 5), spot(9, 8, 5)).build()));
    }

    @Test
    void holdingCountsThePlacePhaseToo() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.breakPhase(DEFAULTS, tick(1).health(9).build()).isEmpty());
        assertFalse(b.holding());
        assertTrue(b.wantsPlacement());

        // 9 - 0 - 5 = 4 < 5
        assertTrue(b.placePhase(9, List.of(spot(1, 10, 5))).isEmpty());
        assertTrue(b.holding());
        assertEquals(Decision.none(Reason.OVER_RESERVE), b.lastDecision());
    }

    @Test
    void aPlacementThatWasNotSentIsNotPending() {
        // The adapter found no crystals to place with by the time it acted, so it never called placed().
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(DEFAULTS, tick(1).candidates(spot(9, 8, 5)).build()));
        // 12 - 0 - 5 = 7
        assertPlaces(8, b.preTick(DEFAULTS, tick(2).health(12).candidates(spot(8, 8, 5)).build()));
        // Only this tick's decision can be confirmed.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> b.placed(9, 0));
    }

    // Next-best fallback

    @Test
    void whenTheBestSpotFailsTheBudgetTheNextOneThatPassesIsPlaced() {
        // Health 9: 10 (self 5) leaves 4 < 5; 8 (self 4) leaves 5; 7 (self 1) would too, but deals less.
        assertDecision(Decision.place(2, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(9).candidates(spot(1, 10, 5), spot(3, 7, 1), spot(2, 8, 4)).build()));
        assertDecision(Decision.place(1, Reason.BUDGET_OFF), new CrystalBrain().preTick(METEOR,
            tick(1).health(9).candidates(spot(1, 10, 5), spot(3, 7, 1), spot(2, 8, 4)).build()));
    }

    @Test
    void whenTheBestOwnCrystalFailsTheFloorTheNextIsTried() {
        // Health 6.5: big leaves 1.5 < 2, small leaves 2.5.
        CrystalSeen big = crystal(1, 10, 5);
        CrystalSeen small = crystal(2, 8, 4);
        CrystalBrain b = new CrystalBrain();
        long t = own(b, 1, big, small);

        assertDecision(Decision.breakCrystal(2, Reason.WITHIN_BUDGET), b.preTick(DEFAULTS, tick(t).health(6.5).crystals(big, small).build()));
        assertFalse(b.holding());

        CrystalBrain withForeign = new CrystalBrain();
        t = own(withForeign, 1, big);
        assertDecision(Decision.breakCrystal(7, Reason.FOREIGN_CRYSTAL),
            withForeign.preTick(DEFAULTS, tick(t).health(6.5).crystals(big, crystal(7, 6, 5)).build()));
    }

    @Test
    void safeModePlacesATinySelfDamageUnderTheReserve() {
        // A foreign crystal of self 5 stands: 7.5 - 5 - 0.5 = 2, under R but at F with self <= epsilon.
        assertDecision(Decision.place(1, Reason.SAFE_SELF_DAMAGE), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(7.5).crystals(crystal(7, 0, 5)).candidates(spot(1, 8, 0.5)).build()));
    }

    // holding() (Q3)

    @Test
    void holdingOnlyWhileEverythingMeteorWouldDoTheBudgetRefuses() {
        // The only spot Meteor accepts leaves 9 - 5 = 4 < 5.
        CrystalBrain refused = new CrystalBrain();
        assertNothing(refused.preTick(DEFAULTS, tick(1).health(9).candidates(spot(1, 10, 5)).build()));
        assertTrue(refused.holding());
        assertEquals(Decision.none(Reason.OVER_RESERVE), refused.lastDecision());
        // The next tick another spot passes (9 - 1 = 8): not holding any more.
        assertPlaces(2, refused.preTick(DEFAULTS, tick(2).health(9).candidates(spot(1, 10, 5), spot(2, 7, 1)).build()));
        assertFalse(refused.holding());

        // Nothing passes Meteor's checks (5.75 < min-damage 6): a real misconfiguration still looks idle.
        CrystalBrain idle = new CrystalBrain();
        assertNothing(idle.preTick(DEFAULTS, tick(1).health(9).candidates(spot(1, 5.75, 5)).build()));
        assertFalse(idle.holding());
        assertEquals(Decision.none(Reason.NOTHING_TO_DO), idle.lastDecision());

        // No target, paused, or the budget off: not holding.
        CrystalBrain noTarget = new CrystalBrain();
        noTarget.preTick(DEFAULTS, tick(1).health(9).targets().candidates(spot(1, 10, 5)).build());
        assertFalse(noTarget.holding());
        CrystalBrain paused = new CrystalBrain();
        paused.preTick(DEFAULTS, tick(1).health(5).candidates(spot(1, 10, 1)).build());
        assertFalse(paused.holding());
        CrystalBrain off = new CrystalBrain();
        assertDecision(Decision.place(1, Reason.BUDGET_OFF), off.preTick(METEOR, tick(1).health(9).candidates(spot(1, 10, 5)).build()));
        assertFalse(off.holding());
    }

    @Test
    void holdingIsTakenAgainEachPreTick() {
        CrystalBrain b = new CrystalBrain();
        b.preTick(DEFAULTS, tick(1).health(9).candidates(spot(1, 10, 5)).build());
        assertTrue(b.holding());

        b.preTick(DEFAULTS, tick(2).health(9).build());
        assertFalse(b.holding());
    }

    @Test
    void totemsNeverChangeADecision() {
        // §1: totems are never counted. 9 - 5 = 4 < 5 with or without them.
        for (int totems : new int[] {0, 8}) {
            CrystalBrain b = new CrystalBrain();
            assertDecision(Decision.place(2, Reason.WITHIN_BUDGET), b.preTick(DEFAULTS,
                tick(1).health(9).totems(totems).candidates(spot(1, 10, 5), spot(2, 7, 1)).build()));
        }
    }

    @Test
    void theReserveAndEpsilonAreSettings() {
        // A reserve of 4: 9 - 5 = 4 >= 4.
        assertDecision(Decision.place(1, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS.toBuilder().reserve(4).build(),
            tick(1).health(9).candidates(spot(1, 10, 5)).build()));
        // epsilon 0.25: the self-0.5 spot is no longer tiny.
        assertNothing(new CrystalBrain().preTick(DEFAULTS.toBuilder().safeSelfDamage(0.25).build(),
            tick(1).health(7.5).crystals(crystal(7, 0, 5)).candidates(spot(1, 8, 0.5)).build()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> DEFAULTS.toBuilder().reserve(1.75).build());
    }
}
