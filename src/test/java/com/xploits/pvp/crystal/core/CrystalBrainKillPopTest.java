package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.assertDecision;
import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.placeTheStuckCrystal;
import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.stuckCrystal;
import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.trustedEnemy;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.player;
import static com.xploits.pvp.crystal.core.Crystals.playerWithHands;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withTotem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B0c (spec Amendment 2026-09-29): the finishing blow pops us only to KILL. A finishing-grade crystal is
 * kill-grade when the target holds no totem in either hand and his hands are visible, pop-grade otherwise.
 * Kill-grade is B0a's override; pop-grade may pass max-damage, the reserve and pause-health but must leave
 * {@code health - C(without this crystal) - self >= FLOOR} (2), so it never pops us, and it does not take the
 * one-at-a-time slot. The target is always reported at 4 (margin 5) and confirmed trusted
 * ({@link CrystalBrainFinishingBlowTest#trustedEnemy}); Balanced risk: reserve 3.5, pause-health 5, max-damage 6.
 */
class CrystalBrainKillPopTest {
    private static final String OTHER = "other";

    /** Balanced, min-damage out of the way: the pop-grade override may NOT go below the reserve here. */
    private static final CrystalSettings LOW_MIN_DAMAGE = CrystalBrainFinishingBlowTest.LOW_MIN_DAMAGE;
    /**
     * The pop-grade override may go below the reserve only at Aggressive (owner's decision 2026-09-29). At
     * Aggressive the reserve equals the floor, so the ordinary budget already takes every pop-grade spot the
     * pop rule would; the only place the pop tier still acts alone is under pause-health, where the ordinary
     * gate is shut. So the pop-rule tests run Aggressive with pause-health raised: only the override can act.
     */
    private static final CrystalSettings POPPING = LOW_MIN_DAMAGE.toBuilder().risk(RiskLevel.AGGRESSIVE).pauseHealth(1000).build();

    /** He holds a totem: a crystal that finishes him only pops him. */
    private static final TargetView POP = playerWithHands(ENEMY, 3, 4, true, true);
    /** No totem, one hand shows an item: a crystal that finishes him kills him. */
    private static final TargetView KILL = playerWithHands(ENEMY, 3, 4, false, true);
    /** Both hands look empty (the server may hide equipment): never read as "no totem". */
    private static final TargetView HIDDEN = playerWithHands(ENEMY, 3, 4, false, false);

    private static final CrystalTick.Hands TOTEM = withTotem(HANDS, true);

    /** Placing spot 3000 (6 to him, {@code self} to us) at {@code health}, our totem and a spare in hand. */
    private static List<Action> placeAt(CrystalBrain b, long t, double health, TargetView foe, double self) {
        return b.preTick(POPPING, tick(t).health(health).hands(TOTEM).targets(foe)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), self)).build());
    }

    // Classification

    @Test
    void aTotemInTheMainHandMakesItAPopAndOnlyTheKillMayPopUs() {
        // Self 100 is past max-damage and the reserve, and would kill us: only kill-grade could place it.
        CrystalBrain pop = new CrystalBrain();
        assertNothing(placeAt(pop, trustedEnemy(pop, 1), 6.5, POP, 100));
        CrystalBrain kill = new CrystalBrain();
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(kill, trustedEnemy(kill, 1), 6.5, KILL, 100));
    }

    @Test
    void bothHandsEmptyIsNotProofOfNoTotem() {
        CrystalBrain hidden = new CrystalBrain();
        assertNothing(placeAt(hidden, trustedEnemy(hidden, 1), 6.5, HIDDEN, 100));
        CrystalBrain b = new CrystalBrain();
        // ... but it is still pop-grade: the floor holds (6.5 - 4.5 = 2), so it is placed.
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(b, trustedEnemy(b, 1), 6.5, HIDDEN, 4.5));
    }

    @Test
    void theAdapterInputsClassifyEachHand() {
        // Totem in the main hand, in the off hand, both empty, and one non-totem item.
        TargetView main = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            true, false, false, true).orElseThrow();
        TargetView off = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            false, true, true, false).orElseThrow();
        TargetView empty = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            false, false, true, true).orElseThrow();
        TargetView item = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            false, false, false, true).orElseThrow();
        TargetView bothItems = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            false, false, false, false).orElseThrow();
        assertTrue(main.totemInHand() && main.handsVisible() && !main.killable());
        assertTrue(off.totemInHand() && off.handsVisible() && !off.killable());
        assertTrue(!empty.totemInHand() && !empty.handsVisible() && !empty.killable());
        assertTrue(!item.totemInHand() && item.handsVisible() && item.killable());
        assertTrue(bothItems.killable());
    }

    @Test
    void aTotemAlwaysCountsAsVisibleEvenIfTheEmptyFlagsSayOtherwise() {
        TargetView t = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            true, false, true, true).orElseThrow();
        assertTrue(t.handsVisible());
        assertFalse(t.killable());
    }

    // Pop-grade: the floor, never a pop

    @Test
    void popGradeIsAllowedAtExactlyTheFloorAndRefusedJustBelow() {
        // Health 6.5, nothing else standing: 6.5 - 4.5 = 2 = FLOOR (below the reserve 3.5, so the ordinary
        // budget refuses it and only the override can place it).
        CrystalBrain at = new CrystalBrain();
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(at, trustedEnemy(at, 1), 6.5, POP, 4.5));
        CrystalBrain below = new CrystalBrain();
        assertNothing(placeAt(below, trustedEnemy(below, 1), 6.5, POP, Math.nextUp(4.5)));
    }

    @Test
    void popGradeGoesPastMaxDamageAndPauseHealthButNeverBelowTheFloor() {
        // Health 9, self 7: past max-damage (6), leaves exactly 2.
        CrystalBrain maxDamage = new CrystalBrain();
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(maxDamage, trustedEnemy(maxDamage, 1), 9, POP, 7));
        CrystalBrain overMax = new CrystalBrain();
        assertNothing(placeAt(overMax, trustedEnemy(overMax, 1), 9, POP, Math.nextUp(7.0)));

        // Health 4 is at or under pause-health (5): the ordinary gate is shut, the pop may still act.
        CrystalBrain paused = new CrystalBrain();
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(paused, trustedEnemy(paused, 1), 4, POP, 2));
        CrystalBrain pausedBelow = new CrystalBrain();
        assertNothing(placeAt(pausedBelow, trustedEnemy(pausedBelow, 1), 4, POP, Math.nextUp(2.0)));
    }

    @Test
    void popGradeCountsWhatElseCouldHitUsInTheFloor() {
        // A foreign standing crystal of self 1.5 is in C: 6.5 - 1.5 - 3 = 2 holds, 6.5 - 1.5 - 3.5 = 1.5 does not.
        CrystalBrain holds = new CrystalBrain();
        long t = trustedEnemy(holds, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), holds.preTick(POPPING,
            tick(t).health(6.5).hands(TOTEM).targets(POP).crystals(crystal(999, 0.0, 1.5))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 3)).build()));
        CrystalBrain refuses = new CrystalBrain();
        long t2 = trustedEnemy(refuses, 1);
        assertNothing(refuses.preTick(POPPING,
            tick(t2).health(6.5).hands(TOTEM).targets(POP).crystals(crystal(999, 0.0, 1.5))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 3.5)).build()));
    }

    @Test
    void popGradeStillNeedsATotemInHandASpareAndTrust() {
        // Positive control in the same fixture (Aggressive, pause-health raised): totem, a spare and trust place it
        // down to the floor (6.5 - 4.5 = 2), so each refusal below is due to its own missing condition alone.
        CrystalBrain control = new CrystalBrain();
        long t0 = trustedEnemy(control, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), control.preTick(POPPING, tick(t0).health(6.5)
            .hands(TOTEM).targets(POP).candidates(spot(3000L, Map.of(ENEMY, 6.0), 4.5)).build()));

        CrystalBrain noTotem = new CrystalBrain();
        long t = trustedEnemy(noTotem, 1);
        assertNothing(noTotem.preTick(POPPING, tick(t).health(6.5).targets(POP)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 4.5)).build()));

        CrystalBrain lastTotem = new CrystalBrain();
        long t2 = trustedEnemy(lastTotem, 1);
        assertNothing(lastTotem.preTick(POPPING, tick(t2).health(6.5).hands(TOTEM).totems(1).targets(POP)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 4.5)).build()));

        // Never confirmed: his reported health is not trusted, so nothing is finishing-grade.
        CrystalBrain untrusted = new CrystalBrain();
        assertNothing(untrusted.preTick(POPPING, tick(1).health(6.5).hands(TOTEM).targets(POP)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 4.5)).build()));
    }

    @Test
    void popGradeDoesNotTakeTheOneAtATimeSlotAndDoesNotNeedItFree() {
        // A pop-grade crystal pending (self 4: 6 - 4 = 2, only the override places it)...
        CrystalBrain popFirst = new CrystalBrain();
        long t = trustedEnemy(popFirst, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), popFirst.preTick(POPPING,
            tick(t).health(6).hands(TOTEM).targets(POP).candidates(spot(3000L, Map.of(ENEMY, 6.0), 4)).build()));
        popFirst.placed(3000L, 0);
        // ... does not block a kill-grade one: 6 - 4 = 2 holds for it (kill does not subtract its own share).
        assertDecision(Decision.place(3001L, Reason.FINISHING_BLOW), popFirst.preTick(POPPING,
            tick(t + 1).health(6).hands(TOTEM).targets(KILL).candidates(spot(3001L, Map.of(ENEMY, 6.0), 1)).build()));

        // A kill-grade crystal pending holds the slot (self 3.75: 6.5 - 3.75 = 2.75 is under the reserve, so only
        // the override places it) ...
        CrystalBrain killFirst = new CrystalBrain();
        long t2 = trustedEnemy(killFirst, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), killFirst.preTick(POPPING,
            tick(t2).health(6.5).hands(TOTEM).targets(KILL).candidates(spot(3000L, Map.of(ENEMY, 6.0), 3.75)).build()));
        killFirst.placed(3000L, 0);
        // ... so a second kill-grade spot whose floor fails is refused (6.5 - 3.75 - 1 = 1.75) ...
        assertNothing(killFirst.preTick(POPPING, tick(t2 + 1).health(6.5).hands(TOTEM).targets(KILL)
            .candidates(spot(3001L, Map.of(ENEMY, 6.0), 1.0)).build()));
        // ... while a pop-grade one that holds the floor (6.5 - 3.75 - 0.75 = 2) is placed: it neither needs nor
        // takes the slot.
        assertDecision(Decision.place(3001L, Reason.FINISHING_BLOW), killFirst.preTick(POPPING,
            tick(t2 + 2).health(6.5).hands(TOTEM).targets(POP).candidates(spot(3001L, Map.of(ENEMY, 6.0), 0.75)).build()));
    }

    // Kill-grade keeps B0a's rule

    @Test
    void killGradeMayGoBelowTheReserveAndPopUsButNeverWithoutASpare() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        // Health 3 (paused), self 100 (would take us to 0): nothing else in flight, so B0a's override places it.
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(b, t, 3, KILL, 100));
        CrystalBrain last = new CrystalBrain();
        long t2 = trustedEnemy(last, 1);
        assertNothing(last.preTick(LOW_MIN_DAMAGE, tick(t2).health(6.5).hands(TOTEM).totems(1).targets(KILL)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));
    }

    // Tier order

    @Test
    void anAllowedFinishingSpotBeatsBothOverrides() {
        CrystalBrain pop = new CrystalBrain();
        long t = trustedEnemy(pop, 1);
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), pop.preTick(LOW_MIN_DAMAGE,
            tick(t).health(20).hands(TOTEM).targets(POP)
                .candidates(spot(3001L, Map.of(ENEMY, 20.0), 100), spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
        CrystalBrain kill = new CrystalBrain();
        long t2 = trustedEnemy(kill, 1);
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), kill.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(20).hands(TOTEM).targets(KILL)
                .candidates(spot(3001L, Map.of(ENEMY, 20.0), 100), spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    /** Both targets confirmed trusted by one crystal that hit both (a drop of 4 each). */
    private static long trustBoth(CrystalBrain b, long t) {
        Map<String, Double> both = Map.of(ENEMY, 6.0, OTHER, 6.0);
        assertPlaces(2000L, b.preTick(DEFAULTS, tick(t).targets(player(ENEMY, 3, 20), player(OTHER, 3, 20))
            .candidates(spot(2000L, both, 0)).build()));
        b.placed(2000L, 0);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(900, 2000L, both, 0.0), 20, HANDS).isEmpty());
        b.targetHurt(ENEMY, 900);
        b.targetHurt(OTHER, 900);
        assertNothing(b.preTick(DEFAULTS, tick(t + 1).targets(player(ENEMY, 3, 16), player(OTHER, 3, 16)).build()));
        return t + 2;
    }

    @Test
    void killGradeBeatsPopGradeEvenWithLessDamage() {
        // Health 6: both spots leave exactly 2 (self 4), both need an override. The pop-grade one (against the
        // target holding a totem) deals far more, and is listed first: the kill-grade one still wins.
        CrystalBrain b = new CrystalBrain();
        long t = trustBoth(b, 1);
        TargetView killTarget = playerWithHands(ENEMY, 3, 4, false, true);
        TargetView popTarget = playerWithHands(OTHER, 3, 4, true, true);
        assertDecision(Decision.place(3001L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(6).hands(TOTEM).targets(killTarget, popTarget)
                .candidates(spot(3000L, Map.of(OTHER, 20.0), 4), spot(3001L, Map.of(ENEMY, 6.0), 4)).build()));
    }

    @Test
    void popGradeIsUsedWhenNoKillGradeSpotIsAvailable() {
        CrystalBrain b = new CrystalBrain();
        long t = trustBoth(b, 1);
        TargetView killTarget = playerWithHands(ENEMY, 3, 4, false, true);
        TargetView popTarget = playerWithHands(OTHER, 3, 4, true, true);
        // The kill-grade spot is out of range, so it is no candidate: the pop-grade one is still placed.
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(POPPING,
            tick(t).health(6).hands(TOTEM).targets(killTarget, popTarget)
                .candidates(spot(3000L, Map.of(OTHER, 20.0), 4), notInRange(spot(3001L, Map.of(ENEMY, 6.0), 4))).build()));
    }

    private static Candidate notInRange(Candidate c) {
        return new Candidate(c.pos(), c.targetDamage(), c.selfDamage(), false, java.util.Set.of(), false);
    }

    // Breaking

    /** Our crystal placed and standing (ours), then measured with {@code self} at the next pre-tick. */
    private static long ownCrystal(CrystalBrain b, long t, int id, long pos) {
        assertPlaces(pos, b.preTick(DEFAULTS, tick(t).candidates(spot(pos, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(pos, 0);
        CrystalSettings noFastBreak = DEFAULTS.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(id, pos, 6.0, 0.0), 20, HANDS).isEmpty());
        return t + 1;
    }

    @Test
    void breakingAPopGradeCrystalKeepsTheFloorAndNeverPopsUs() {
        // Health 4 (pause-health shuts the ordinary break): an own crystal of self 2 leaves exactly 2.
        CrystalBrain at = new CrystalBrain();
        long t = ownCrystal(at, trustedEnemy(at, 1), 950, 3000L);
        assertDecision(Decision.breakCrystal(950, Reason.FINISHING_BLOW), at.preTick(POPPING,
            tick(t).health(4).hands(TOTEM).targets(POP).crystals(crystal(950, 3000L, 6.0, 2.0)).build()));
        assertEquals(Map.of(950, FinishKind.POP), at.finishingCrystalKinds());

        CrystalBrain below = new CrystalBrain();
        long t2 = ownCrystal(below, trustedEnemy(below, 1), 950, 3000L);
        assertNothing(below.preTick(POPPING, tick(t2).health(4).hands(TOTEM).targets(POP)
            .crystals(crystal(950, 3000L, 6.0, Math.nextUp(2.0))).build()));

        // Past max-damage: health 9, self 7 leaves 2.
        CrystalBrain big = new CrystalBrain();
        long t3 = ownCrystal(big, trustedEnemy(big, 1), 950, 3000L);
        // B1: max-damage no longer keeps it from the normal budget, but here pause-health is raised, so only the
        // pop override acts (Aggressive), and it meets the floor exactly.
        assertDecision(Decision.breakCrystal(950, Reason.FINISHING_BLOW), big.preTick(POPPING,
            tick(t3).health(9).hands(TOTEM).targets(POP).crystals(crystal(950, 3000L, 6.0, 7.0)).build()));
    }

    private static final Map<String, Double> P_DAMAGE = Map.of(OTHER, 20.0);
    private static final Map<String, Double> K_DAMAGE = Map.of(ENEMY, 6.0);

    /**
     * Both targets trusted, then our own crystals standing: P (id 950, base 3000, 20 to OTHER) and, if asked, K
     * (id 951, base 3001, 6 to ENEMY). Returns the next free pre-tick.
     */
    private static long ownCrystalsPAndK(CrystalBrain b, boolean withK) {
        long t = trustBoth(b, 1);
        CrystalSettings noFastBreak = DEFAULTS.toBuilder().fastBreak(false).build();
        assertPlaces(3000L, b.preTick(DEFAULTS, tick(t).targets(player(ENEMY, 3, 20), player(OTHER, 3, 20))
            .candidates(spot(3000L, P_DAMAGE, 0)).build()));
        b.placed(3000L, 0);
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, P_DAMAGE, 0.0), 20, HANDS).isEmpty());
        if (!withK) return t + 1;
        // P stays listed (a crystal not listed is gone), harmless for now, so it does not hold the placing gate.
        assertPlaces(3001L, b.preTick(DEFAULTS, tick(t + 1).targets(player(ENEMY, 3, 20), player(OTHER, 3, 20))
            .crystals(crystal(950, 3000L, Map.of(OTHER, 0.0), 0.0)).candidates(spot(3001L, K_DAMAGE, 0)).build()));
        b.placed(3001L, 0);
        assertTrue(b.crystalAdded(noFastBreak, crystal(951, 3001L, K_DAMAGE, 0.0), 20, HANDS).isEmpty());
        return t + 2;
    }

    @Test
    void breakingTakesTheKillGradeCrystalBeforeAPopGradeOneWithMoreDamage() {
        // Health 16, own crystals of self 7 (past max-damage, so only the override may break either): popping
        // crystal P (20 to the target holding a totem) leaves 16 - 7 - 7 = 2, killing crystal K leaves 9.
        TargetView killTarget = playerWithHands(ENEMY, 3, 4, false, true);
        TargetView popTarget = playerWithHands(OTHER, 3, 4, true, true);

        // Sanity: on its own, P is broken (B1: by the normal budget now, 16 - 7 = 9 >= 2; the pop rule, which
        // asks 9 - 7 = 2, never takes a crystal the budget already allows, so it is not marked).
        CrystalBrain alone = new CrystalBrain();
        long t = ownCrystalsPAndK(alone, false);
        assertDecision(Decision.breakCrystal(950, Reason.WITHIN_BUDGET), alone.preTick(LOW_MIN_DAMAGE,
            tick(t).health(16).hands(TOTEM).targets(killTarget, popTarget)
                .crystals(crystal(950, 3000L, P_DAMAGE, 7.0)).build()));
        assertEquals(Map.of(), alone.finishingCrystalKinds());

        // With K standing too, both are within the normal budget (a break asks 16 - 7 >= 2 of each, the crystals
        // standing do not count for a break), so B1's tier 1 takes the one with more damage, P, before any
        // override; K, kill-grade, would only go first where the budget refused both (nothing here does).
        CrystalBrain both = new CrystalBrain();
        long t2 = ownCrystalsPAndK(both, true);
        assertDecision(Decision.breakCrystal(950, Reason.WITHIN_BUDGET), both.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(16).hands(TOTEM).targets(killTarget, popTarget)
                .crystals(crystal(950, 3000L, P_DAMAGE, 7.0), crystal(951, 3001L, K_DAMAGE, 7.0)).build()));
        assertEquals(Map.of(), both.finishingCrystalKinds());
    }

    @Test
    void breakingAKillGradeCrystalMayPopUsAndIsMarkedKill() {
        CrystalBrain b = new CrystalBrain();
        long t = ownCrystal(b, trustedEnemy(b, 1), 950, 3000L);
        assertDecision(Decision.breakCrystal(950, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(4).hands(TOTEM).targets(KILL).crystals(crystal(950, 3000L, 6.0, 100.0)).build()));
        assertEquals(Map.of(950, FinishKind.KILL), b.finishingCrystalKinds());
        assertEquals(java.util.Set.of(950), b.finishingCrystalIds());
    }

    @Test
    void aPopGradeBreakNeverLowersAKillMark() {
        // Placed through the kill override, then broken again while the target holds a totem: still KILL.
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(9).hands(TOTEM).targets(KILL).candidates(spot(3000L, Map.of(ENEMY, 6.0), 7)).build()));
        b.placed(3000L, 0);
        CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, 7.0), 9, TOTEM).isEmpty());
        assertEquals(Map.of(950, FinishKind.KILL), b.finishingCrystalKinds());
        // B1: the normal budget breaks it now (9 - 7 = 2), and never lowers the mark.
        assertDecision(Decision.breakCrystal(950, Reason.WITHIN_BUDGET), b.preTick(LOW_MIN_DAMAGE,
            tick(t + 1).health(9).hands(TOTEM).targets(POP).crystals(crystal(950, 3000L, 6.0, 7.0)).build()));
        assertEquals(Map.of(950, FinishKind.KILL), b.finishingCrystalKinds());
    }

    @Test
    void aPlacedPopGradeCrystalIsExposedAsPop() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(b, t, 6.5, POP, 4.5));
        b.placed(3000L, 0);
        CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, 4.5), 6.5, TOTEM).isEmpty());
        assertEquals(Map.of(950, FinishKind.POP), b.finishingCrystalKinds());
    }

    @Test
    void fastBreakServesBothKindsWithTheirOwnRules() {
        // Health 9, an own crystal of self 7 (past max-damage): pop keeps the floor (9 - 7 = 2), kill needs none.
        for (double self : new double[] {7.0, Math.nextUp(7.0)}) {
            CrystalBrain b = new CrystalBrain();
            long t = trustedEnemy(b, 1);
            assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), b.preTick(LOW_MIN_DAMAGE,
                tick(t).health(9).hands(TOTEM).targets(POP).candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
            b.placed(3000L, 0);
            // An idle pre-tick resets "rotated" (fast-break waits for the next one after a rotation).
            assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t + 1).health(9).hands(TOTEM).targets(POP).build()));
            var action = b.crystalAdded(LOW_MIN_DAMAGE, crystal(950, 3000L, 6.0, self), 9, TOTEM);
            if (self == 7.0) {
                assertEquals(Decision.breakCrystal(950, Reason.WITHIN_BUDGET), action.orElseThrow().decision());
            } else {
                assertTrue(action.isEmpty());
            }
        }
        CrystalBrain kill = new CrystalBrain();
        long t = trustedEnemy(kill, 1);
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), kill.preTick(LOW_MIN_DAMAGE,
            tick(t).health(9).hands(TOTEM).targets(KILL).candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
        kill.placed(3000L, 0);
        assertNothing(kill.preTick(LOW_MIN_DAMAGE, tick(t + 1).health(9).hands(TOTEM).targets(KILL).build()));
        assertEquals(Decision.breakCrystal(950, Reason.FINISHING_BLOW),
            kill.crystalAdded(LOW_MIN_DAMAGE, crystal(950, 3000L, 6.0, 100.0), 9, TOTEM).orElseThrow().decision());
    }

    // F1's stuck crystal: broken by the override only when it is kill-grade

    @Test
    void theStuckCrystalIsBrokenWhenTheTargetIsKillGrade() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        long t2 = placeTheStuckCrystal(b, t, 9000L, 9500);
        assertDecision(Decision.breakCrystal(9500, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(6).hands(TOTEM).targets(KILL).crystals(stuckCrystal(9500, 9000L)).build()));
    }

    @Test
    void theStuckCrystalIsNotBrokenWhenTheTargetOnlyPopsAndTheGateOpens() {
        // 6 - 5.542... = 0.458 < FLOOR: the pop rule refuses it, as does the ordinary budget.
        for (TargetView foe : new TargetView[] {POP, HIDDEN}) {
            CrystalBrain b = new CrystalBrain();
            long t = trustedEnemy(b, 1);
            long t2 = placeTheStuckCrystal(b, t, 9000L, 9500);
            CrystalSeen stuck = stuckCrystal(9500, 9000L);
            assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t2).health(6).hands(TOTEM).targets(foe).crystals(stuck).build()));
            // F1's behaviour stays: the crystal no longer closes the gate, only a spot of exactly zero self damage.
            assertDecision(Decision.place(9002L, Reason.SAFE_SELF_DAMAGE), b.preTick(LOW_MIN_DAMAGE,
                tick(t2 + 1).health(6).targets(foe).crystals(stuck).candidates(spot(9002L, 6.0, 0)).build()));
            CrystalBrain other = new CrystalBrain();
            long u = trustedEnemy(other, 1);
            long u2 = placeTheStuckCrystal(other, u, 9000L, 9500);
            assertNothing(other.preTick(LOW_MIN_DAMAGE, tick(u2).health(6).hands(TOTEM).targets(foe)
                .crystals(stuckCrystal(9500, 9000L)).candidates(spot(9003L, 6.0, 0.5)).build()));
        }
    }

    // Fix round 1: a kill-grade crystal whose kill rule fails is checked by the pop rules

    /** A kill-grade crystal placed through the kill override, pending, holding the slot (self 3.75 at 6.5). */
    private static long slotTakenByAKillCrystal(CrystalBrain b) {
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(6.5).hands(TOTEM).targets(KILL).candidates(spot(3000L, Map.of(ENEMY, 6.0), 3.75)).build()));
        b.placed(3000L, 0);
        return t + 1;
    }

    @Test
    void aKillGradeSpotWithTheSlotTakenIsPlacedAsPopWhenTheFloorHoldsAndRefusedWhenItFails() {
        CrystalBrain holds = new CrystalBrain();
        long t = slotTakenByAKillCrystal(holds);
        // 6.5 - 3.75 - 0.75 = 2 holds.
        assertDecision(Decision.place(3001L, Reason.FINISHING_BLOW), holds.preTick(POPPING,
            tick(t).health(6.5).hands(TOTEM).targets(KILL).candidates(spot(3001L, Map.of(ENEMY, 6.0), 0.75)).build()));
        holds.placed(3001L, 0);
        CrystalSettings noFastBreak = POPPING.toBuilder().fastBreak(false).build();
        assertTrue(holds.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, 3.75), 6.5, TOTEM).isEmpty());
        assertTrue(holds.crystalAdded(noFastBreak, crystal(951, 3001L, 6.0, 0.75), 6.5, TOTEM).isEmpty());
        // The first keeps its kill mark, the downgraded one is a pop: no second slot taken.
        assertEquals(Map.of(950, FinishKind.KILL, 951, FinishKind.POP), holds.finishingCrystalKinds());

        CrystalBrain fails = new CrystalBrain();
        long t2 = slotTakenByAKillCrystal(fails);
        assertNothing(fails.preTick(POPPING, tick(t2).health(6.5).hands(TOTEM).targets(KILL)
            .candidates(spot(3001L, Map.of(ENEMY, 6.0), 1.0)).build()));
    }

    /**
     * Health 6 (paused): own crystal B was placed earlier at full health (id 960, base 3100), then own crystal A
     * (id 950, base 3000) through the kill override, which holds the slot. Returns the next free pre-tick.
     */
    private static long slotTakenWithAnotherOwnCrystal(CrystalBrain b) {
        long t = trustedEnemy(b, 1);
        CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
        assertDecision(Decision.place(3100L, Reason.WITHIN_BUDGET), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(20).targets(KILL).candidates(spot(3100L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(3100L, 0);
        assertTrue(b.crystalAdded(noFastBreak, crystal(960, 3100L, Map.of(ENEMY, 0.0), 0.0), 20, HANDS).isEmpty());
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t + 1).health(6).hands(TOTEM).targets(KILL).crystals(crystal(960, 3100L, Map.of(ENEMY, 0.0), 0.0))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 4)).build()));
        b.placed(3000L, 0);
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, Map.of(ENEMY, 0.5), 4.0), 6, TOTEM).isEmpty());
        return t + 2;
    }

    @Test
    void aKillGradeBreakWithTheSlotTakenIsBrokenAsPopWhenTheFloorHoldsAndRefusedWhenItFails() {
        // Health 16; A (self 4, no damage now) holds the slot; B (self 7, past max-damage) is kill-grade. C
        // without B = 4: 16 - 4 - 7 = 5 holds, so B is broken as a pop.
        CrystalBrain holds = new CrystalBrain();
        long t = slotTakenWithAnotherOwnCrystal(holds);
        assertDecision(Decision.breakCrystal(960, Reason.WITHIN_BUDGET), holds.preTick(LOW_MIN_DAMAGE,
            tick(t).health(16).hands(TOTEM).targets(KILL)
                .crystals(crystal(950, 3000L, Map.of(ENEMY, 0.5), 4.0), crystal(960, 3100L, Map.of(ENEMY, 6.0), 7.0))
                .build()));
        assertEquals(FinishKind.KILL, holds.finishingCrystalKinds().get(950));
        assertNull(holds.finishingCrystalKinds().get(960));

        // Self 15: the budget refuses it (16 - 15 = 1 < FLOOR), 16 - 4 - 15 fails the pop rule, and the slot is
        // taken: refused. (Self 11 used to show this through max-damage; the budget allows it now.)
        CrystalBrain fails = new CrystalBrain();
        long t2 = slotTakenWithAnotherOwnCrystal(fails);
        assertNothing(fails.preTick(LOW_MIN_DAMAGE, tick(t2).health(16).hands(TOTEM).targets(KILL)
            .crystals(crystal(950, 3000L, Map.of(ENEMY, 0.5), 4.0), crystal(960, 3100L, Map.of(ENEMY, 6.0), 15.0))
            .build()));
    }

    @Test
    void aKillGradeFastBreakWithTheSlotTakenFallsBackToThePopRules() {
        for (double self : new double[] {7.0, 15.0}) {
            CrystalBrain b = new CrystalBrain();
            long t = trustedEnemy(b, 1);
            // B placed at full health first (ours), then A through the kill override at health 6.
            assertDecision(Decision.place(3100L, Reason.WITHIN_BUDGET), b.preTick(LOW_MIN_DAMAGE,
                tick(t).health(20).targets(KILL).candidates(spot(3100L, Map.of(ENEMY, 6.0), 0)).build()));
            b.placed(3100L, 0);
            assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
                tick(t + 1).health(6).hands(TOTEM).targets(KILL).candidates(spot(3000L, Map.of(ENEMY, 6.0), 4)).build()));
            b.placed(3000L, 0);
            CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
            assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t + 2).health(16).hands(TOTEM).targets(KILL).build()));
            assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, Map.of(ENEMY, 0.5), 4.0), 16, TOTEM).isEmpty());
            var action = b.crystalAdded(LOW_MIN_DAMAGE, crystal(960, 3100L, 6.0, self), 16, TOTEM);
            if (self == 7.0) {
                assertEquals(Decision.breakCrystal(960, Reason.WITHIN_BUDGET), action.orElseThrow().decision());
                assertNull(b.finishingCrystalKinds().get(960));
            } else {
                assertTrue(action.isEmpty());
            }
        }
    }

    @Test
    void withTwoTargetsTheSelfPopIsJustifiedOnlyByTheKillableOne() {
        TargetView killTarget = playerWithHands(ENEMY, 3, 4, false, true);
        TargetView popTarget = playerWithHands(OTHER, 3, 4, true, true);
        // A spot that only finishes the totem holder cannot pop us (self 100 refused) ...
        CrystalBrain onlyPop = new CrystalBrain();
        long t = trustBoth(onlyPop, 1);
        assertNothing(onlyPop.preTick(LOW_MIN_DAMAGE, tick(t).health(6.5).hands(TOTEM).targets(killTarget, popTarget)
            .candidates(spot(3000L, Map.of(OTHER, 20.0), 100)).build()));
        // ... one that finishes the killable one may (and is picked over the bigger pop-only spot).
        CrystalBrain both = new CrystalBrain();
        long t2 = trustBoth(both, 1);
        assertDecision(Decision.place(3001L, Reason.FINISHING_BLOW), both.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(6.5).hands(TOTEM).targets(killTarget, popTarget)
                .candidates(spot(3000L, Map.of(OTHER, 20.0), 100), spot(3001L, Map.of(ENEMY, 6.0), 100)).build()));
    }

    @Test
    void whenTheHandsChangeAKillMarkedCrystalIsBrokenOnlyIfItMeetsThePopFloor() {
        for (double self : new double[] {7.0, 7.5}) {
            CrystalBrain b = new CrystalBrain();
            long t = trustedEnemy(b, 1);
            // Placed through the kill override (health 9, self 7 is past max-damage) ...
            assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
                tick(t).health(9).hands(TOTEM).targets(KILL).candidates(spot(3000L, Map.of(ENEMY, 6.0), 7)).build()));
            b.placed(3000L, 0);
            CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
            assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, self), 9, TOTEM).isEmpty());
            assertEquals(Map.of(950, FinishKind.KILL), b.finishingCrystalKinds());
            // ... then he equips a totem: the pop floor (9 - 7 = 2) decides.
            List<Action> actions = b.preTick(LOW_MIN_DAMAGE, tick(t + 1).health(9).hands(TOTEM).targets(POP)
                .crystals(crystal(950, 3000L, 6.0, self)).build());
            if (self == 7.0) assertDecision(Decision.breakCrystal(950, Reason.WITHIN_BUDGET), actions);
            else assertNothing(actions);
            assertEquals(Map.of(950, FinishKind.KILL), b.finishingCrystalKinds());
        }
    }

    @Test
    void ownDeathProtectionCountsOnlyATotemStillCarryingTheComponent() {
        assertTrue(ServerValues.ownDeathProtection(true, true));
        assertFalse(ServerValues.ownDeathProtection(true, false));
        assertFalse(ServerValues.ownDeathProtection(false, true));
        assertFalse(ServerValues.ownDeathProtection(false, false));
    }

    @Test
    void aTargetHoldingAnyDeathProtectionItemIsPopGrade() {
        // The adapter passes "has the death_protection component" for each hand; the core only sees the flag.
        TargetView custom = ServerValues.target("p", 9, 4, 0, TargetView.NO_ARMOR, false, true, false,
            true, false, false, false).orElseThrow();
        assertTrue(custom.totemInHand() && !custom.killable());
    }

    // Parity

    @Test
    void withFinishingBlowOffOrTheBudgetOffNothingChanges() {
        // Health 9, self 7: the pop override places it; with finishing-blow off, or the budget off, it is
        // refused as today (past max-damage 6).
        CrystalBrain on = new CrystalBrain();
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(on, trustedEnemy(on, 1), 9, POP, 7));

        CrystalSettings off = LOW_MIN_DAMAGE.toBuilder().finishingBlow(false).build();
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertNothing(b.preTick(off, tick(t).health(9).hands(TOTEM).targets(POP)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 7)).build()));

        CrystalSettings noBudget = METEOR.toBuilder().minDamage(1).build();
        CrystalBrain m = new CrystalBrain();
        long t2 = trustedEnemy(m, 1);
        assertNothing(m.preTick(noBudget, tick(t2).health(9).hands(TOTEM).targets(POP)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 7)).build()));
    }
}
