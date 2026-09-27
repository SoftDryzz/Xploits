package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.player;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withRaw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks R3-3 and R3-11 (research-external M1-M3, 3a; research-offense-gap): with the self-budget on, a placement
 * the target's hurt window would surely swallow is held back. "Surely" is learned from our own crystals: each one
 * we placed and then saw gone is a landing of D pre-ticks, from the pre-tick we first decided to place it to the
 * first one that finds it gone. A {@link Fight} places and removes crystals with the landings a test gives; the
 * last one is {@link #OURS}, which hits the enemy for a raw {@link #RAW1}. Its full-hit packet counts from the
 * pre-tick before the one that finds it gone, the window's pre-tick {@code w}, and its own landing is a sample too.
 * At pre-tick {@code w + k} there are two spots: the best one ({@link #BEST}, 10 damage) whose raw to the enemy is
 * what each test says, and a worse one ({@link #NEXT}, 9 damage) whose raw is above {@code RAW1 - 0.5}, so it is
 * never held back. The best spot is chosen unless it is held.
 */
class CrystalBrainTargetWindowTest {
    private static final int OURS = 7;
    private static final int FOREIGN = 9;
    private static final double RAW1 = 47.7;
    private static final long BEST = 20;
    private static final long NEXT = 21;
    private static final String OTHER = "other";
    /** The ping {@link Fight} places with: its placements stay pending long enough for any landing it makes. */
    private static final int PENDING_PING = 30;

    /**
     * A fight, pre-tick by pre-tick from 1: our crystals placed and seen gone, then the window one of them opens, and
     * the pre-ticks after it.
     */
    private static final class Fight {
        final CrystalBrain brain = new CrystalBrain();
        CrystalSettings settings;
        /** The next pre-tick to run. */
        long next = 1;
        /** The pre-tick the enemy's hit counts from. */
        long window = -1;
        private int nextId = 100;

        Fight(CrystalSettings settings) {
            this.settings = settings;
        }

        /** Our crystals, one after another, each seen gone this many pre-ticks after its placement was decided. */
        Fight land(int... landings) {
            for (int d : landings) land(d, nextId++, Map.of(ENEMY, RAW1), PENDING_PING, false);
            return this;
        }

        /**
         * One crystal placed at the next pre-tick (and, when {@code placedAgain}, again on the same base at the one
         * after, as Meteor does until the crystal comes), which appears after pre-tick {@code next + d - 1} and is
         * removed before pre-tick {@code next + d}, which finds it gone.
         */
        Fight land(int d, int id, Map<String, Double> raw, int ping, boolean placedAgain) {
            long pos = 1000L + id;
            long placedAt = next;
            place(placedAt, pos, ping);
            if (placedAgain) place(placedAt + 1, pos, ping);
            for (long t = placedAt + (placedAgain ? 2 : 1); t < placedAt + d; t++) idle(t);
            brain.crystalAdded(settings, withRaw(crystal(id, pos, 8, 0), raw), 20, HANDS);
            brain.crystalRemoved(id);
            next = placedAt + d;
            return this;
        }

        private void place(long at, long pos, int ping) {
            Decision d = only(brain.preTick(settings, tick(at).candidates(spot(pos, 8, 0)).build())).decision();
            assertEquals(Decision.Kind.PLACE, d.kind());
            assertEquals(pos, d.ref());
            brain.placed(pos, ping);
        }

        private void idle(long t) {
            assertTrue(brain.preTick(settings, tick(t).build()).isEmpty(), "pre-tick " + t);
        }

        /** Pre-ticks with nothing to do up to, not including, {@code t}. */
        Fight idleUntil(long t) {
            for (; next < t; next++) idle(next);
            return this;
        }

        /** {@link #OURS} lands {@code d} pre-ticks after its placement and hits the enemy with this raw1. */
        Fight opens(int d, Map<String, Double> raw1) {
            land(d, OURS, raw1, PENDING_PING, false);
            brain.targetHurt(ENEMY, OURS);
            window = next - 1;
            return this;
        }

        Fight opens(int d) {
            return opens(d, Map.of(ENEMY, RAW1));
        }

        /** Idle up to pre-tick {@code w + k}, and return that pre-tick's facts to fill in. */
        Crystals.Tick at(int k) {
            idleUntil(window + k);
            next = window + k + 1;
            return tick(window + k);
        }

        /** Pre-tick {@code w + k} with the two spots; the best one deals {@code raw2} raw to the enemy. */
        List<Action> spotsAt(int k, int ping, double raw2) {
            return brain.preTick(settings, at(k).ping(ping).candidates(
                withRaw(spot(BEST, 10, 0), raw2), withRaw(spot(NEXT, 9, 0), 47.5)).build());
        }

        List<Action> spotsAt(int k, double raw2) {
            return spotsAt(k, 0, raw2);
        }
    }

    /** Four landings of 3 and the window's own: five of 3, so a spot is held up to k = 5 (5 + 3 + 1 = 9 < 10). */
    private static Fight windowFromOurCrystal() {
        return new Fight(DEFAULTS).land(3, 3, 3, 3).opens(3);
    }

    private static long placedOn(List<Action> actions) {
        Decision d = only(actions).decision();
        assertEquals(Decision.Kind.PLACE, d.kind());
        return d.ref();
    }

    // Held

    @Test
    void aSpotTheWindowWouldSwallowIsHeldAndTheNextOneChosen() {
        Fight f = windowFromOurCrystal();
        assertEquals(NEXT, placedOn(f.spotsAt(2, 47.0)));
        assertEquals(1, f.brain.deferredForTargetWindow());
    }

    @Test
    void fiveLandingsOfThreeHoldUpToKFiveOnly() {
        // 5 + 3 + 1 = 9 < 10; 6 + 3 + 1 = 10.
        assertEquals(NEXT, placedOn(windowFromOurCrystal().spotsAt(5, 47.0)));
        Fight f = windowFromOurCrystal();
        assertEquals(BEST, placedOn(f.spotsAt(6, 47.0)));
        assertEquals(0, f.brain.deferredForTargetWindow());
    }

    @Test
    void theSlowestLandingSetsTheBound() {
        // 2, 2, 2, 2, 6 and the window's own 2: 2 + 6 + 1 = 9 < 10; 3 + 6 + 1 = 10.
        assertEquals(NEXT, placedOn(new Fight(DEFAULTS).land(2, 2, 2, 6).opens(2).spotsAt(2, 47.0)));
        assertEquals(BEST, placedOn(new Fight(DEFAULTS).land(2, 2, 2, 6).opens(2).spotsAt(3, 47.0)));
    }

    @Test
    void thePingNoLongerCounts() {
        // Learned landings of 3: an unknown ping (5) still holds at k = 2 (two round trips would already be the
        // whole window), and no ping no longer holds at k = 6 (6 + 0 + 2 < 10 did).
        assertEquals(NEXT, placedOn(windowFromOurCrystal().spotsAt(2, CrystalBrain.UNKNOWN_PING_TICKS, 47.0)));
        assertEquals(NEXT, placedOn(windowFromOurCrystal().spotsAt(1, 3, 47.0)));
        assertEquals(BEST, placedOn(windowFromOurCrystal().spotsAt(6, 0, 47.0)));
    }

    /** Four landings of 2, then the window's crystal placed at pre-tick 9, again at 10, and seen gone at 12. */
    private static Fight placedAgain() {
        Fight f = new Fight(DEFAULTS).land(2, 2, 2, 2);
        f.land(3, OURS, Map.of(ENEMY, RAW1), PENDING_PING, true);
        f.brain.targetHurt(ENEMY, OURS);
        f.window = f.next - 1;
        return f;
    }

    @Test
    void aLandingCountsFromTheFirstPlacementOnTheBase() {
        // Placed at 9 and again at 10 (the server missed the first), seen gone at 12: a landing of 3, not 2, so it
        // is held up to k = 5 only (6 + 3 + 1 = 10; counted from 10 it would be 6 + 2 + 1 = 9).
        assertEquals(NEXT, placedOn(placedAgain().spotsAt(5, 47.0)));
        assertEquals(BEST, placedOn(placedAgain().spotsAt(6, 47.0)));
    }

    @Test
    void aHeldSpotIsNotARefusalOfTheBudget() {
        Fight f = windowFromOurCrystal();
        assertTrue(f.brain.preTick(DEFAULTS, f.at(2).ping(0).candidates(withRaw(spot(BEST, 10, 0), 47.0)).build()).isEmpty());
        assertFalse(f.brain.holding());
        assertEquals(Decision.none(Reason.NOTHING_TO_DO), f.brain.lastDecision());
        assertEquals(1, f.brain.deferredForTargetWindow());
    }

    // Not held: too few landings, or old, slow or not ours

    @Test
    void withFourLandingsNothingIsHeld() {
        // Three landings of 3 and the window's own: four.
        Fight f = new Fight(DEFAULTS).land(3, 3, 3).opens(3);
        assertEquals(BEST, placedOn(f.spotsAt(1, 47.0)));
        assertEquals(0, f.brain.deferredForTargetWindow());
    }

    @Test
    void aLandingOlderThanTwoHundredTicksNoLongerCounts() {
        // The first landing is seen at pre-tick 4; the window's crystal placed at 200 or 201 is seen gone at 203 or
        // 204, so the spots at k = 2 come at 204 or 205: the first landing 200 ticks old, then 201.
        Fight kept = new Fight(DEFAULTS).land(3, 3, 3, 3).idleUntil(200).opens(3);
        assertEquals(NEXT, placedOn(kept.spotsAt(2, 47.0)));
        Fight dropped = new Fight(DEFAULTS).land(3, 3, 3, 3).idleUntil(201).opens(3);
        assertEquals(BEST, placedOn(dropped.spotsAt(2, 47.0)));
    }

    @Test
    void aLateOwnCrystalIsNotALanding() {
        // Placed with no ping (pending for 5 pre-ticks) and seen 7 later: a late own crystal. Counted, it would make
        // five landings with a slowest of 7, and k = 1 would hold (1 + 7 + 1 = 9 < 10).
        Fight f = new Fight(DEFAULTS).land(3, 3, 3);
        f.land(7, 150, Map.of(ENEMY, RAW1), 0, false);
        f.opens(3);
        assertEquals(1, f.brain.lateOwnCrystals());
        assertEquals(BEST, placedOn(f.spotsAt(1, 47.0)));
    }

    @Test
    void aLandingSlowerThanTwentyIsIgnored() {
        // Four of 3, one of 25 and the window's own 3: five of 3 still hold at k = 5. Clamped to 20, it would not.
        assertEquals(NEXT, placedOn(new Fight(DEFAULTS).land(3, 3, 3, 3, 25).opens(3).spotsAt(5, 47.0)));
        // And it is not a fifth landing either.
        assertEquals(BEST, placedOn(new Fight(DEFAULTS).land(3, 3, 3, 25).opens(3).spotsAt(1, 47.0)));
    }

    // Not held: the window

    @Test
    void aSpotThatWouldRaiseTheHitByMoreThanTheMarginIsPlaced() {
        // 47.3 > 47.7 - 0.5.
        Fight f = windowFromOurCrystal();
        assertEquals(BEST, placedOn(f.spotsAt(2, 47.3)));
        assertEquals(0, f.brain.deferredForTargetWindow());
    }

    @Test
    void aWindowOpenedByACrystalThatIsNotOursNeverHolds() {
        Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3, 3);
        CrystalSeen foreign = withRaw(crystal(FOREIGN, 5, 8, 0), RAW1);
        assertEquals(Decision.Kind.BREAK, only(f.brain.preTick(DEFAULTS, tick(f.next).crystals(foreign).build())).decision().kind());
        f.brain.crystalRemoved(FOREIGN);
        f.brain.targetHurt(ENEMY, FOREIGN);
        f.window = f.next;
        f.next++;
        assertEquals(BEST, placedOn(f.spotsAt(2, 47.0)));
    }

    @Test
    void aWindowFromAnUnknownSourceNeverHolds() {
        for (int source : new int[] {CrystalBrain.NO_SOURCE, 12345}) {
            Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3, 3).idleUntil(17);
            f.brain.targetHurt(ENEMY, source);
            f.window = 16;
            assertEquals(BEST, placedOn(f.spotsAt(2, 47.0)), "source " + source);
        }
    }

    @Test
    void anotherHitAfterOursClosesOurWindow() {
        for (int source : new int[] {CrystalBrain.NO_SOURCE, 12345}) {
            Fight f = windowFromOurCrystal();
            f.brain.targetHurt(ENEMY, source);
            assertEquals(BEST, placedOn(f.spotsAt(2, 47.0)), "source " + source);
        }
        // A foreign crystal the brain knows, with a raw of its own: still not ours.
        Fight f = windowFromOurCrystal();
        CrystalSeen foreign = withRaw(crystal(FOREIGN, 5, 0.5, 0), RAW1);
        assertTrue(f.brain.preTick(DEFAULTS, f.at(1).crystals(foreign).build()).isEmpty());
        f.brain.targetHurt(ENEMY, FOREIGN);
        assertEquals(BEST, placedOn(f.brain.preTick(DEFAULTS, f.at(2).ping(0).crystals(foreign)
            .candidates(withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    @Test
    void anotherHitOnAnotherPlayerLeavesTheEnemysWindowOpen() {
        Fight f = windowFromOurCrystal();
        f.brain.targetHurt(OTHER, CrystalBrain.NO_SOURCE);
        assertEquals(NEXT, placedOn(f.spotsAt(2, 47.0)));
    }

    @Test
    void withNoRaw1RecordedNothingIsHeld() {
        assertEquals(BEST, placedOn(new Fight(DEFAULTS).land(3, 3, 3, 3).opens(3, Map.of()).spotsAt(2, 47.0)));
    }

    @Test
    void withNoRaw2MeasuredNothingIsHeld() {
        Fight f = windowFromOurCrystal();
        assertEquals(BEST, placedOn(f.brain.preTick(DEFAULTS, f.at(2).ping(0).candidates(spot(BEST, 10, 0),
            withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    @Test
    void aSecondTargetOutsideAnyWindowStillTakesTheFullHit() {
        Fight f = windowFromOurCrystal();
        Candidate both = withRaw(spot(BEST, Map.of(ENEMY, 5.0, OTHER, 5.0), 0), Map.of(ENEMY, 47.0, OTHER, 47.0));
        Candidate next = withRaw(spot(NEXT, Map.of(ENEMY, 9.0), 0), Map.of(ENEMY, 47.5));
        assertEquals(BEST, placedOn(f.brain.preTick(DEFAULTS, f.at(2).ping(0).targets(Crystals.enemy(), player(OTHER, 3, 20))
            .candidates(both, next).build())));
    }

    @Test
    void aSecondTargetTheSpotDoesNotHurtDoesNotStopTheHold() {
        Fight f = windowFromOurCrystal();
        Candidate enemyOnly = withRaw(spot(BEST, Map.of(ENEMY, 10.0, OTHER, 0.0), 0), Map.of(ENEMY, 47.0));
        Candidate next = withRaw(spot(NEXT, Map.of(ENEMY, 9.0), 0), Map.of(ENEMY, 47.5));
        assertEquals(NEXT, placedOn(f.brain.preTick(DEFAULTS, f.at(2).ping(0).targets(Crystals.enemy(), player(OTHER, 3, 20))
            .candidates(enemyOnly, next).build())));
    }

    @Test
    void withTheBudgetOffNothingIsHeld() {
        Fight f = new Fight(METEOR).land(3, 3, 3, 3).opens(3);
        assertEquals(Decision.place(BEST, Reason.BUDGET_OFF), only(f.spotsAt(2, 47.0)).decision());
        assertEquals(0, f.brain.deferredForTargetWindow());
    }

    @Test
    void withTheBudgetOffNoLandingIsLearned() {
        // Five landings with the budget off, then the window's crystal with it on: one landing, so nothing is held.
        Fight f = new Fight(METEOR).land(3, 3, 3, 3, 3);
        f.settings = DEFAULTS;
        f.opens(3);
        assertEquals(BEST, placedOn(f.spotsAt(2, 47.0)));
    }

    @Test
    void evenTheFastestLandingsHoldNoLaterThanKSeven() {
        // Five landings of 1: 7 + 1 + 1 = 9 < 10; 8 + 1 + 1 = 10, before the window itself is over at k = 10.
        Fight f = new Fight(DEFAULTS).land(1, 1, 1, 1).opens(1);
        assertEquals(NEXT, placedOn(new Fight(DEFAULTS).land(1, 1, 1, 1).opens(1).spotsAt(7, 47.0)));
        assertEquals(BEST, placedOn(f.spotsAt(8, 47.0)));
        assertEquals(0, f.brain.deferredForTargetWindow());
    }

    // Breaks

    @Test
    void breaksAreNeverHeld() {
        Fight f = windowFromOurCrystal();
        CrystalSeen foreign = withRaw(crystal(FOREIGN, 5, 10, 0), 47.0);
        assertEquals(Decision.breakCrystal(FOREIGN, Reason.FOREIGN_CRYSTAL),
            only(f.brain.preTick(DEFAULTS, f.at(2).ping(0).crystals(foreign).build())).decision());
    }

    // Gaps, the budget turned off, and when a hit counts from

    @Test
    void forgettingTheWindowsHoldsNothingBack() {
        // The window's pre-tick is 15. Forgotten, then five landings of 1 learned again, so only the hit decides:
        // kept, it would hold at k = 6 and 7 (7 + 1 + 1 = 9 < 10).
        // A hit handed over but not yet counted.
        Fight queued = windowFromOurCrystal();
        queued.brain.forgetWindows();
        assertEquals(BEST, placedOn(queued.land(1, 1, 1, 1, 1).spotsAt(6, 47.0)));

        // A window already open.
        Fight open = windowFromOurCrystal();
        assertTrue(open.brain.preTick(DEFAULTS, open.at(1).build()).isEmpty());
        open.brain.forgetWindows();
        assertEquals(BEST, placedOn(open.land(1, 1, 1, 1, 1).spotsAt(7, 47.0)));
    }

    @Test
    void forgettingTheWindowsForgetsTheLandings() {
        // Five landings, forgotten; then four more, and the window's crystal: five after the gap, so it holds.
        Fight after = new Fight(DEFAULTS).land(3, 3, 3, 3, 3);
        after.brain.forgetWindows();
        assertEquals(NEXT, placedOn(after.land(3, 3, 3, 3).opens(3).spotsAt(2, 47.0)));
        // Three more only: four after the gap, and the five before it no longer count.
        Fight before = new Fight(DEFAULTS).land(3, 3, 3, 3, 3);
        before.brain.forgetWindows();
        assertEquals(BEST, placedOn(before.land(3, 3, 3).opens(3).spotsAt(2, 47.0)));
    }

    @Test
    void aCrystalPlacedBeforeAGapIsNotALanding() {
        // Placed, then a gap in the pre-ticks (the adapter forgets), then seen gone: its landing is not known, as the
        // pre-ticks skipped are not counted. With three more and the window's crystal: four, so nothing is held.
        Fight f = new Fight(DEFAULTS);
        f.place(1, 1150, PENDING_PING);
        f.brain.forgetWindows();
        f.idle(2);
        f.brain.crystalAdded(DEFAULTS, withRaw(crystal(150, 1150, 8, 0), RAW1), 20, HANDS);
        f.brain.crystalRemoved(150);
        f.next = 3;
        assertEquals(BEST, placedOn(f.land(3, 3, 3).opens(3).spotsAt(1, 47.0)));
    }

    @Test
    void aPreTickWithTheBudgetOffForgetsTheWindowsAndTheLandings() {
        // The hit handed over, then a pre-tick with the budget off: dropped, and nothing held once it is on again,
        // even with five landings learned since (kept, the window would hold at k = 7: 7 + 1 + 1 = 9 < 10).
        Fight queued = windowFromOurCrystal();
        assertTrue(queued.brain.preTick(METEOR, queued.at(1).build()).isEmpty());
        assertEquals(BEST, placedOn(queued.land(1, 1, 1, 1, 1).spotsAt(7, 47.0)));

        // The window open, then one pre-tick with the budget off: k = 3 would still hold the spot.
        Fight open = windowFromOurCrystal();
        assertTrue(open.brain.preTick(DEFAULTS, open.at(1).build()).isEmpty());
        assertTrue(open.brain.preTick(METEOR, open.at(2).build()).isEmpty());
        assertEquals(BEST, placedOn(open.spotsAt(3, 47.0)));

        // Five landings, then a pre-tick with the budget off, then four more with it on: four, nothing held.
        Fight learned = new Fight(DEFAULTS).land(3, 3, 3, 3, 3);
        learned.settings = METEOR;
        learned.idleUntil(learned.next + 1);
        learned.settings = DEFAULTS;
        assertEquals(BEST, placedOn(learned.land(3, 3, 3).opens(3).spotsAt(2, 47.0)));

        // A crystal placed with the budget on, seen gone after a pre-tick with it off: not a landing either.
        Fight across = new Fight(DEFAULTS);
        across.place(1, 1150, PENDING_PING);
        across.settings = METEOR;
        across.idle(2);
        across.settings = DEFAULTS;
        across.brain.crystalAdded(DEFAULTS, withRaw(crystal(150, 1150, 8, 0), RAW1), 20, HANDS);
        across.brain.crystalRemoved(150);
        across.next = 3;
        assertEquals(BEST, placedOn(across.land(3, 3, 3).opens(3).spotsAt(1, 47.0)));
    }

    @Test
    void aHitCountsFromThePreTickBeforeTheOneThatTakesIt() {
        // Handed over during pre-tick w (after its break phase): not counted in w itself, and counted from w by
        // pre-tick w + 1, so with landings of 3 k = 5 still holds the spot and k = 6 does not.
        for (int k : new int[] {5, 6}) {
            Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3);
            f.land(3, OURS, Map.of(ENEMY, RAW1), PENDING_PING, false);
            long w = f.next;
            assertTrue(f.brain.breakPhase(DEFAULTS, tick(w).ping(0).build()).isEmpty());
            assertTrue(f.brain.wantsPlacement());
            f.brain.targetHurt(ENEMY, OURS);
            assertEquals(BEST, placedOn(f.brain.placePhase(20, List.of(withRaw(spot(BEST, 10, 0), 47.0),
                withRaw(spot(NEXT, 9, 0), 47.5))).stream().toList()));
            f.window = w;
            f.next = w + 1;
            long expected = k == 5 ? NEXT : BEST;
            assertEquals(expected, placedOn(f.spotsAt(k, 47.0)), "k " + k);
        }
    }

    @Test
    void aHitHandedOverBeforeTheFirstPreTickIsDropped() {
        CrystalBrain brain = new CrystalBrain();
        brain.targetHurt(ENEMY, OURS);
        assertTrue(brain.preTick(DEFAULTS, tick(1).build()).isEmpty());
        assertEquals(BEST, placedOn(brain.preTick(DEFAULTS, tick(2).ping(0).candidates(
            withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    // R3-16: the worst-case reach reads the same landings, learned the same way

    @Test
    void withNoLandingsLearnedYetTheBoundIsTheDocumentedFallback() {
        assertEquals(TargetWindows.LANDING_SAMPLE_MAX_TICKS, new CrystalBrain().landingTicksBound());
    }

    @Test
    void withFewerThanFiveLandingsTheBoundIsStillTheFallback() {
        Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3);
        f.idle(f.next);
        assertEquals(TargetWindows.LANDING_SAMPLE_MAX_TICKS, f.brain.landingTicksBound());
    }

    @Test
    void theBoundIsTheSlowestOfTheLandingsLearned() {
        Fight f = new Fight(DEFAULTS).land(2, 2, 2, 2, 6);
        f.idle(f.next);
        assertEquals(6, f.brain.landingTicksBound());
    }

    @Test
    void anOutlierLandingNeverSetsTheBoundAndIsNotCountedEither() {
        // The 25 is dropped outright as an ordinary sample (TargetWindowsTest): only four real samples remain,
        // so the fallback still applies, exactly as withFewerThanFiveLandingsTheBoundIsStillTheFallback above
        // (it also marks a recent outlier, but that floors the bound at the same fallback value here, so it is
        // not visible in this particular case — see the next test for where it is).
        Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3, 25);
        f.idle(f.next);
        assertEquals(TargetWindows.LANDING_SAMPLE_MAX_TICKS, f.brain.landingTicksBound());
    }

    @Test
    void anOutlierAfterFiveNormalSamplesRaisesTheBoundToTheCeiling() {
        // review-r3-16.md's Important #2 repro: five ordinary landings of 3 first (establishing a small bound
        // on their own), then a real 25-tick landing (a lag spike). The bound must rise to the documented
        // ceiling, not stay at 3 as if the lag spike had never happened.
        Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3, 3);
        f.idle(f.next);
        f.next++;
        assertEquals(3, f.brain.landingTicksBound(), "established first: the bound from the five samples alone");
        f.land(25, 999, Map.of(ENEMY, RAW1), PENDING_PING, false);
        f.idle(f.next);
        assertEquals(TargetWindows.LANDING_SAMPLE_MAX_TICKS, f.brain.landingTicksBound());
    }

    @Test
    void agedOutLandingsFallBackToTheDocumentedBound() {
        Fight f = new Fight(DEFAULTS).land(3, 3, 3, 3, 3);
        f.idle(f.next);
        f.next++;
        assertEquals(3, f.brain.landingTicksBound());
        f.idleUntil(f.next + 250);
        assertEquals(TargetWindows.LANDING_SAMPLE_MAX_TICKS, f.brain.landingTicksBound());
    }

    @Test
    void withTheBudgetOffNoLandingIsLearnedForTheBoundEither() {
        Fight f = new Fight(METEOR).land(3, 3, 3, 3, 3);
        f.idle(f.next);
        assertEquals(TargetWindows.LANDING_SAMPLE_MAX_TICKS, f.brain.landingTicksBound());
    }
}
