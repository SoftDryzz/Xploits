package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
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
 * Task R3-3 (research-external M1-M3, 3a): with the self-budget on, a placement the target's hurt window
 * would swallow is held back. Our crystal (id {@link #OURS}, placed on base 1 at pre-tick 1, broken at
 * pre-tick 2) hits the enemy for a raw {@link #RAW1}; its full-hit packet is read after pre-tick 2, so at
 * pre-tick {@code 2 + k} it arrived {@code k} ticks ago. Then two spots: the best one ({@link #BEST}, 10
 * damage) whose raw to the enemy is what each test says, and a worse one ({@link #NEXT}, 9 damage) whose raw
 * is above {@code RAW1 - 0.5}, so it is never held back. The best spot is chosen unless it is deferred.
 */
class CrystalBrainTargetWindowTest {
    private static final int OURS = 7;
    private static final int FOREIGN = 9;
    private static final double RAW1 = 47.7;
    private static final long BEST = 20;
    private static final long NEXT = 21;
    private static final String OTHER = "other";

    /** A brain whose crystal {@link #OURS} opened the enemy's window at pre-tick 2, with this raw1 recorded. */
    private static CrystalBrain windowFromOurCrystal(CrystalSettings settings, Map<String, Double> raw1) {
        CrystalBrain brain = ourCrystalGone(settings, raw1);
        brain.targetHurt(ENEMY, OURS);
        return brain;
    }

    /** A brain whose crystal {@link #OURS} was placed at pre-tick 1 and broken at pre-tick 2, with no hit read yet. */
    private static CrystalBrain ourCrystalGone(CrystalSettings settings, Map<String, Double> raw1) {
        CrystalBrain brain = new CrystalBrain();
        assertEquals(Decision.place(1, settings.selfBudget() ? Reason.WITHIN_BUDGET : Reason.BUDGET_OFF),
            only(brain.preTick(settings, tick(1).candidates(spot(1, 8, 0)).build())).decision());
        brain.placed(1, 0);
        CrystalSeen ours = withRaw(crystal(OURS, 1, 8, 0), raw1);
        assertEquals(Decision.Kind.BREAK, only(brain.preTick(settings, tick(2).crystals(ours).build())).decision().kind());
        brain.crystalRemoved(OURS);
        return brain;
    }

    private static CrystalBrain windowFromOurCrystal() {
        return windowFromOurCrystal(DEFAULTS, Map.of(ENEMY, RAW1));
    }

    /** Pre-ticks with nothing to do until the one before {@code 2 + k}. */
    private static void idle(CrystalBrain brain, CrystalSettings settings, int k) {
        for (int t = 3; t < 2 + k; t++) assertTrue(brain.preTick(settings, tick(t).build()).isEmpty());
    }

    /** Pre-tick {@code 2 + k} with the two spots; the best one deals {@code raw2} raw to the enemy. */
    private static List<Action> spotsAt(CrystalBrain brain, CrystalSettings settings, int k, int ping, double raw2) {
        idle(brain, settings, k);
        return brain.preTick(settings, tick(2 + k).ping(ping).candidates(
            withRaw(spot(BEST, 10, 0), raw2), withRaw(spot(NEXT, 9, 0), 47.5)).build());
    }

    private static long placedOn(List<Action> actions) {
        Decision d = only(actions).decision();
        assertEquals(Decision.Kind.PLACE, d.kind());
        return d.ref();
    }

    // Deferred

    @Test
    void aSpotTheWindowWouldSwallowIsHeldAndTheNextOneChosen() {
        CrystalBrain brain = windowFromOurCrystal();
        assertEquals(NEXT, placedOn(spotsAt(brain, DEFAULTS, 2, 0, 47.0)));
        assertEquals(1, brain.deferredForTargetWindow());
    }

    @Test
    void theLastTickThatSurelyLandsInsideStillDefers() {
        // 7 + 0 + 2 = 9 < 10 with no ping; 1 + 2 * 3 + 2 = 9 < 10 with a ping of 3.
        assertEquals(NEXT, placedOn(spotsAt(windowFromOurCrystal(), DEFAULTS, 7, 0, 47.0)));
        assertEquals(NEXT, placedOn(spotsAt(windowFromOurCrystal(), DEFAULTS, 1, 3, 47.0)));
    }

    @Test
    void aHeldSpotIsNotARefusalOfTheBudget() {
        CrystalBrain brain = windowFromOurCrystal();
        idle(brain, DEFAULTS, 2);
        assertTrue(brain.preTick(DEFAULTS, tick(4).ping(0).candidates(withRaw(spot(BEST, 10, 0), 47.0)).build()).isEmpty());
        assertFalse(brain.holding());
        assertEquals(Decision.none(Reason.NOTHING_TO_DO), brain.lastDecision());
        assertEquals(1, brain.deferredForTargetWindow());
    }

    // Not deferred

    @Test
    void aSpotThatWouldRaiseTheHitByMoreThanTheMarginIsPlaced() {
        // 47.3 > 47.7 - 0.5.
        CrystalBrain brain = windowFromOurCrystal();
        assertEquals(BEST, placedOn(spotsAt(brain, DEFAULTS, 2, 0, 47.3)));
        assertEquals(0, brain.deferredForTargetWindow());
    }

    @Test
    void aLandingThatMayMissTheWindowIsPlaced() {
        // 8 + 0 + 2 = 10, not < 10; 2 + 2 * 3 + 2 = 10.
        assertEquals(BEST, placedOn(spotsAt(windowFromOurCrystal(), DEFAULTS, 8, 0, 47.0)));
        assertEquals(BEST, placedOn(spotsAt(windowFromOurCrystal(), DEFAULTS, 2, 3, 47.0)));
    }

    @Test
    void anUnknownPingNeverDefers() {
        // 2 * 5 = 10 already: nothing lands surely inside.
        assertEquals(BEST, placedOn(spotsAt(windowFromOurCrystal(), DEFAULTS, 1, CrystalBrain.UNKNOWN_PING_TICKS, 47.0)));
        assertEquals(BEST, placedOn(spotsAt(windowFromOurCrystal(), DEFAULTS, 2, CrystalBrain.pingTicks(CrystalBrain.UNKNOWN_LATENCY), 47.0)));
    }

    @Test
    void aWindowOpenedByACrystalThatIsNotOursNeverDefers() {
        CrystalBrain brain = new CrystalBrain();
        CrystalSeen foreign = withRaw(crystal(FOREIGN, 5, 8, 0), RAW1);
        assertEquals(Decision.Kind.BREAK, only(brain.preTick(DEFAULTS, tick(2).crystals(foreign).build())).decision().kind());
        brain.crystalRemoved(FOREIGN);
        brain.targetHurt(ENEMY, FOREIGN);
        assertEquals(BEST, placedOn(spotsAt(brain, DEFAULTS, 2, 0, 47.0)));
    }

    @Test
    void aWindowFromAnUnknownSourceNeverDefers() {
        for (int source : new int[] {CrystalBrain.NO_SOURCE, 12345}) {
            CrystalBrain brain = new CrystalBrain();
            assertTrue(brain.preTick(DEFAULTS, tick(2).build()).isEmpty());
            brain.targetHurt(ENEMY, source);
            assertEquals(BEST, placedOn(spotsAt(brain, DEFAULTS, 2, 0, 47.0)), "source " + source);
        }
    }

    @Test
    void anotherHitAfterOursClosesOurWindow() {
        for (int source : new int[] {CrystalBrain.NO_SOURCE, 12345}) {
            CrystalBrain brain = windowFromOurCrystal();
            brain.targetHurt(ENEMY, source);
            assertEquals(BEST, placedOn(spotsAt(brain, DEFAULTS, 2, 0, 47.0)), "source " + source);
        }
        // A foreign crystal the brain knows, with a raw of its own: still not ours.
        CrystalBrain brain = windowFromOurCrystal();
        assertTrue(brain.preTick(DEFAULTS, tick(3).crystals(withRaw(crystal(FOREIGN, 5, 0.5, 0), RAW1)).build()).isEmpty());
        brain.targetHurt(ENEMY, FOREIGN);
        assertEquals(BEST, placedOn(brain.preTick(DEFAULTS, tick(4).ping(0).crystals(withRaw(crystal(FOREIGN, 5, 0.5, 0), RAW1))
            .candidates(withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    @Test
    void anotherHitOnAnotherPlayerLeavesTheEnemysWindowOpen() {
        CrystalBrain brain = windowFromOurCrystal();
        brain.targetHurt(OTHER, CrystalBrain.NO_SOURCE);
        assertEquals(NEXT, placedOn(spotsAt(brain, DEFAULTS, 2, 0, 47.0)));
    }

    @Test
    void withNoRaw1RecordedNothingIsDeferred() {
        assertEquals(BEST, placedOn(spotsAt(windowFromOurCrystal(DEFAULTS, Map.of()), DEFAULTS, 2, 0, 47.0)));
    }

    @Test
    void withNoRaw2MeasuredNothingIsDeferred() {
        CrystalBrain brain = windowFromOurCrystal();
        idle(brain, DEFAULTS, 2);
        assertEquals(BEST, placedOn(brain.preTick(DEFAULTS, tick(4).ping(0).candidates(spot(BEST, 10, 0),
            withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    @Test
    void aSecondTargetOutsideAnyWindowStillTakesTheFullHit() {
        CrystalBrain brain = windowFromOurCrystal();
        idle(brain, DEFAULTS, 2);
        Candidate both = withRaw(spot(BEST, Map.of(ENEMY, 5.0, OTHER, 5.0), 0), Map.of(ENEMY, 47.0, OTHER, 47.0));
        Candidate next = withRaw(spot(NEXT, Map.of(ENEMY, 9.0), 0), Map.of(ENEMY, 47.5));
        assertEquals(BEST, placedOn(brain.preTick(DEFAULTS, tick(4).ping(0).targets(Crystals.enemy(), player(OTHER, 3, 20))
            .candidates(both, next).build())));
    }

    @Test
    void aSecondTargetTheSpotDoesNotHurtDoesNotStopTheDeferral() {
        CrystalBrain brain = windowFromOurCrystal();
        idle(brain, DEFAULTS, 2);
        Candidate enemyOnly = withRaw(spot(BEST, Map.of(ENEMY, 10.0, OTHER, 0.0), 0), Map.of(ENEMY, 47.0));
        Candidate next = withRaw(spot(NEXT, Map.of(ENEMY, 9.0), 0), Map.of(ENEMY, 47.5));
        assertEquals(NEXT, placedOn(brain.preTick(DEFAULTS, tick(4).ping(0).targets(Crystals.enemy(), player(OTHER, 3, 20))
            .candidates(enemyOnly, next).build())));
    }

    @Test
    void withTheBudgetOffNothingIsDeferred() {
        CrystalBrain brain = windowFromOurCrystal(METEOR, Map.of(ENEMY, RAW1));
        List<Action> actions = spotsAt(brain, METEOR, 2, 0, 47.0);
        assertEquals(Decision.place(BEST, Reason.BUDGET_OFF), only(actions).decision());
        assertEquals(0, brain.deferredForTargetWindow());
    }

    @Test
    void theWindowExpires() {
        // The same window as the one that defers at k = 2, eight ticks on: 8 + 0 + 2 = 10.
        CrystalBrain brain = windowFromOurCrystal();
        assertEquals(BEST, placedOn(spotsAt(brain, DEFAULTS, 8, 0, 47.0)));
        assertEquals(0, brain.deferredForTargetWindow());
    }

    // Breaks

    @Test
    void breaksAreNeverDeferred() {
        CrystalBrain brain = windowFromOurCrystal();
        idle(brain, DEFAULTS, 2);
        CrystalSeen foreign = withRaw(crystal(FOREIGN, 5, 10, 0), 47.0);
        assertEquals(Decision.breakCrystal(FOREIGN, Reason.FOREIGN_CRYSTAL),
            only(brain.preTick(DEFAULTS, tick(4).ping(0).crystals(foreign).build())).decision());
    }

    // Fix round 1: gaps, the budget turned off, and when a hit counts from

    @Test
    void forgettingTheWindowsHoldsNothingBack() {
        // A hit handed over but not yet counted, and a window already open: both forgotten.
        CrystalBrain queued = windowFromOurCrystal();
        queued.forgetWindows();
        assertEquals(BEST, placedOn(spotsAt(queued, DEFAULTS, 2, 0, 47.0)));

        CrystalBrain open = windowFromOurCrystal();
        assertTrue(open.preTick(DEFAULTS, tick(3).build()).isEmpty());
        open.forgetWindows();
        assertEquals(BEST, placedOn(open.preTick(DEFAULTS, tick(4).ping(0).candidates(
            withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    @Test
    void aPreTickWithTheBudgetOffForgetsTheWindows() {
        // The hit handed over, then a pre-tick with the budget off: dropped, and nothing held once it is on again.
        CrystalBrain queued = windowFromOurCrystal();
        assertTrue(queued.preTick(METEOR, tick(3).build()).isEmpty());
        assertEquals(BEST, placedOn(queued.preTick(DEFAULTS, tick(4).ping(0).candidates(
            withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())));

        // The window open, then one pre-tick with the budget off: k = 3 would still hold the spot.
        CrystalBrain open = windowFromOurCrystal();
        assertTrue(open.preTick(DEFAULTS, tick(3).build()).isEmpty());
        assertTrue(open.preTick(METEOR, tick(4).build()).isEmpty());
        assertEquals(BEST, placedOn(open.preTick(DEFAULTS, tick(5).ping(0).candidates(
            withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())));
    }

    @Test
    void aHitCountsFromThePreTickBeforeTheOneThatTakesIt() {
        // Handed over during pre-tick 3 (after its break phase): not counted in pre-tick 3 itself, and counted from 3
        // by pre-tick 4, so k = 7 at pre-tick 10 still holds the spot and k = 8 at pre-tick 11 does not.
        for (int at : new int[] {10, 11}) {
            CrystalBrain brain = ourCrystalGone(DEFAULTS, Map.of(ENEMY, RAW1));
            assertTrue(brain.breakPhase(DEFAULTS, tick(3).ping(0).build()).isEmpty());
            assertTrue(brain.wantsPlacement());
            brain.targetHurt(ENEMY, OURS);
            assertEquals(BEST, placedOn(brain.placePhase(20, List.of(withRaw(spot(BEST, 10, 0), 47.0),
                withRaw(spot(NEXT, 9, 0), 47.5))).stream().toList()));
            for (int t = 4; t < at; t++) assertTrue(brain.preTick(DEFAULTS, tick(t).build()).isEmpty());
            long expected = at == 10 ? NEXT : BEST;
            assertEquals(expected, placedOn(brain.preTick(DEFAULTS, tick(at).ping(0).candidates(
                withRaw(spot(BEST, 10, 0), 47.0), withRaw(spot(NEXT, 9, 0), 47.5)).build())), "pre-tick " + at);
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
}
