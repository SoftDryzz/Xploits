package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.SAFE_DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.outOfBreakRange;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withBudget;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task R3-15 (research-double-count.md): once a crystal's hit on us is certain to already be in health (a
 * full-hit packet naming it, AND health has since dropped by at least {@link CrystalBrain#CONFIRM_FRACTION} of
 * its predicted {@code budgetSelfDamage}), it leaves I for good instead of staying counted for its full window.
 *
 * <p>Reserve R = 5 ({@link Crystals#SAFE_DEFAULTS}). {@link #FIVE} and {@link #SIX} each have
 * {@code budgetSelfDamage} 5, so a lone crystal needs a drop of exactly 4 to be confirmed; both together need 8.
 * Every crystal is placed out of break range, so ++ never attacks it itself: what happens to it (vanishing,
 * dealing us a full hit) is entirely the test's to say, exactly as a foreign or already-exploding crystal would.
 * A probe spot's {@code budgetSelfDamage} is set apart from Meteor's own ({@link Crystals#withBudget}, self 0
 * there), so only the budget's own checks decide it, never Meteor's max-damage or anti-suicide.
 */
class CrystalBrainConfirmedHitTest {
    private static final CrystalSeen FIVE = outOfBreakRange(crystal(5, 8, 5));
    private static final CrystalSeen SIX = outOfBreakRange(crystal(6, 8, 5));
    private static final CrystalSeen SEVEN = outOfBreakRange(crystal(7, 8, 5));

    private static void assertDecision(Decision expected, List<Action> actions) {
        assertEquals(expected, only(actions).decision());
    }

    /** Crystal(s) present at pre-tick 1, gone by pre-tick 2 (health unchanged at 20 throughout). */
    private static CrystalBrain vanished(CrystalSeen... crystals) {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(crystals).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).build()).isEmpty());
        return b;
    }

    // The two proofs together

    @Test
    void aConfirmedHitLeavesTheBudgetForGood() {
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5);
        // 20 - 16 = 4, exactly 0.8 * 5: confirmed, so I is empty. 16 - 0 - 8 = 8 >= 5.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(16).candidates(withBudget(spot(9, 10, 0), 8)).build()));
    }

    @Test
    void aDropOfOnlySeventyPercentKeepsItCounted() {
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5);
        // 20 - 16.5 = 3.5, 0.7 * 5: not enough. FIVE stays in I: 16.5 - 5 - 8 = 3.5 < 5.
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(16.5).candidates(withBudget(spot(9, 10, 0), 8)).build()).isEmpty());
    }

    @Test
    void aDropWithNoPacketKeepsItCounted() {
        // The same drop as the confirmed case, but selfHurt was never called: nothing to attribute it to.
        CrystalBrain b = vanished(FIVE);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(16).candidates(withBudget(spot(9, 10, 0), 8)).build()).isEmpty());
    }

    @Test
    void aPacketForADifferentCrystalKeepsItCounted() {
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(999); // no crystal known by that id
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(16).candidates(withBudget(spot(9, 10, 0), 8)).build()).isEmpty());
    }

    @Test
    void usesTheExactBudgetSelfDamageNotMeteorsTruncatedOne() {
        // Meteor's own selfDamage is 4 (would need only 0.8 * 4 = 3.2), but the budget's exact value is 5
        // (needs 4): a drop of 3.5 clears the wrong threshold but not the right one.
        CrystalSeen c = outOfBreakRange(withBudget(crystal(5, 8, 4), 5));
        CrystalBrain b = vanished(c);
        b.selfHurt(5);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(16.5).candidates(withBudget(spot(9, 10, 0), 8)).build()).isEmpty());
    }

    @Test
    void noNumberOfTicksAloneEverConfirmsWithoutAMatchingDrop() {
        // A timeout alone (research-double-count.md's prototype) would eventually confirm this; health never
        // drops here, so the fix (two proofs, never a timeout alone) never does, for as long as the crystal is
        // still known at all (it leaves I on its own after the disappearance window regardless).
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5);
        // Self 11, not 8: with no drop at all, health stays 20, and 20 - 5 - 8 would already pass regardless of
        // confirmation, proving nothing. 20 - 5 - 11 = 4 < 5, so only excluding FIVE could ever pass this one.
        var probe = withBudget(spot(9, 10, 0), 11);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(probe).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(probe).build()).isEmpty());
    }

    // Several of our crystals hitting in the same tick

    @Test
    void twoCrystalsWhosePacketsArriveTogetherAreConfirmedOnlyTogether() {
        var probe = withBudget(spot(9, 10, 0), 7);
        // Required together: 0.8 * (5 + 5) = 8.

        CrystalBrain both = vanished(FIVE, SIX);
        both.selfHurt(5);
        both.selfHurt(6);
        // 20 - 12 = 8: enough for the sum, so both leave I. 12 - 0 - 7 = 5 >= 5.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            both.preTick(SAFE_DEFAULTS, tick(3).health(12).candidates(probe).build()));

        CrystalBrain partial = vanished(FIVE, SIX);
        partial.selfHurt(5);
        partial.selfHurt(6);
        // 20 - 16 = 4: only one crystal's worth, not the sum of both: neither leaves I. 16 - 10 - 7 = -1 < 5.
        assertTrue(partial.preTick(SAFE_DEFAULTS, tick(3).health(16).candidates(probe).build()).isEmpty());
    }

    // Crystals whose packets are stamped a tick apart (fix round 1, review-r3-15.md Critical): they must never
    // be checked against the same later health independently, only in packet order against one shared baseline.

    @Test
    void twoCrystalsGoneOneTickApartAreConfirmedInPacketOrderNotTogether() {
        // review-r3-15.md's exact repro. Baseline 20 (FIVE's, the earliest); required 4 each, 8 for both.

        // Only FIVE's damage has synced: health 15 is fully explained by FIVE alone (drop 5 >= 4). SIX's own 4
        // more would need a combined drop of 8, not yet observed, so it must stay in I: C = 5 (SIX only).
        // Not under-excluded (C = 10, neither confirmed, self 1 so it would fail there: 15 - 10 - 1 = 4 < 5):
        // 15 - 5 - 1 = 9 >= 5 passes.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            twoStaggered().preTick(SAFE_DEFAULTS, tick(4).health(15).candidates(withBudget(spot(9, 10, 0), 1)).build()));
        // Not over-excluded either (C = 0, both wrongly confirmed, the bug this fixes): 15 - 5 - 7 = 3 < 5 must
        // still refuse.
        assertTrue(twoStaggered().preTick(SAFE_DEFAULTS, tick(4).health(15).candidates(withBudget(spot(9, 10, 0), 7)).build()).isEmpty());

        // Once the drop covers both (20 - 10 = 10 >= 4 + 4 = 8), SIX confirms too: C = 0. 10 - 0 - 2 = 8 >= 5.
        CrystalBrain later = twoStaggered();
        assertTrue(later.preTick(SAFE_DEFAULTS, tick(4).health(15).build()).isEmpty()); // advance past tick 4 first
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            later.preTick(SAFE_DEFAULTS, tick(5).health(10).candidates(withBudget(spot(9, 10, 0), 2)).build()));
    }

    /** FIVE and SIX present, then gone one pre-tick apart (2, 3), each handing over its packet as it goes. */
    private static CrystalBrain twoStaggered() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(FIVE, SIX).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).crystals(SIX).build()).isEmpty()); // FIVE gone
        b.selfHurt(5);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).build()).isEmpty()); // SIX gone too, no sync yet
        b.selfHurt(6);
        return b;
    }

    @Test
    void threeStaggeredCrystalsConfirmOneAtATimeAsTheDropAllows() {
        // Baseline 20 throughout (FIVE's, the earliest of the three). Required 4 each, 12 for all three; C after
        // n confirmed = 5 * (3 - n).

        // Drop 5 (health 15): only FIVE fits (0 + 4 <= 5); SIX would need 4 + 4 = 8. C = 10 (SIX, SEVEN pending).
        // Not under-excluded (C = 15, none confirmed, self 0 so it would still fail there: 15 - 15 - 0 = 0 < 5):
        // 15 - 10 - 0 = 5 >= 5 passes.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            threeStaggered().preTick(SAFE_DEFAULTS, tick(5).health(15).candidates(withBudget(spot(9, 10, 0), 0)).build()));
        // Not over-excluded (C = 5 or C = 0, self 1 so either would pass: 15 - 5 - 1 = 9, 15 - 0 - 1 = 14, both
        // >= 5): 15 - 10 - 1 = 4 < 5 must refuse.
        assertTrue(threeStaggered().preTick(SAFE_DEFAULTS, tick(5).health(15).candidates(withBudget(spot(9, 10, 0), 1)).build()).isEmpty());

        // Drop 10 (health 10): FIVE (0 + 4), then SIX (4 + 4 = 8) both fit; SEVEN would need 8 + 4 = 12. C = 5.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            threeStaggered().preTick(SAFE_DEFAULTS, tick(5).health(10).candidates(withBudget(spot(9, 10, 0), 0)).build()));
        // Not over-excluded (C = 0): 10 - 0 - 4 = 6 >= 5 would still pass.
        assertTrue(threeStaggered().preTick(SAFE_DEFAULTS, tick(5).health(10).candidates(withBudget(spot(9, 10, 0), 4)).build()).isEmpty());

        // Drop 13 (health 7): all three fit (8 + 4 = 12 <= 13). C = 0.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            threeStaggered().preTick(SAFE_DEFAULTS, tick(5).health(7).candidates(withBudget(spot(9, 10, 0), 1)).build()));
    }

    /** FIVE, SIX and SEVEN present, then gone one pre-tick apart (2, 3, 4), each handing over its packet as it goes. */
    private static CrystalBrain threeStaggered() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(FIVE, SIX, SEVEN).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).crystals(SIX, SEVEN).build()).isEmpty());
        b.selfHurt(5);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).crystals(SEVEN).build()).isEmpty());
        b.selfHurt(6);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(4).health(20).build()).isEmpty());
        b.selfHurt(7);
        return b;
    }

    /**
     * A crystal we never placed (no pending placement when it appeared) is foreign: {@code breakAllowed} uses
     * Meteor's rules only for it ({@code Verdict.FOREIGN}, skipping the budget's own health read), proving
     * {@code Known.ours} is false. It still gets confirmed through {@code selfHurt}/{@code confirmSelfHits} on
     * exactly the same terms as one of ours: neither reads {@code ours} at all, the same as {@code targetHurt}.
     */
    @Test
    void aForeignCrystalGoingThroughSelfHurtIsConfirmedOnTheSameTerms() {
        CrystalBrain b = new CrystalBrain();
        assertDecision(Decision.breakCrystal(5, Reason.FOREIGN_CRYSTAL),
            b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(crystal(5, 8, 5)).build()));
        b.attackSent();
        // Gone (an enemy could break their own crystal, or it could explode on its own): a full-hit packet
        // naming it arrives.
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).build()).isEmpty());
        b.selfHurt(5);
        // 20 - 16 = 4, exactly 0.8 * 5: confirmed. 16 - 0 - 8 = 8 >= 5.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(16).candidates(withBudget(spot(9, 10, 0), 8)).build()));
    }

    // Absorption/regeneration/totem pops invalidate that tick's confirmation, never permanently

    @Test
    void aTotemPopInvalidatesThatTicksConfirmationButNotTheNextOne() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).totems(1).crystals(FIVE).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).totems(1).build()).isEmpty());
        b.selfHurt(5);

        // Totems 1 -> 0: a pop. The drop from the original baseline (20 - 15 = 5) would otherwise be more than
        // enough (required 4). 15 - 5 - 8 = 2 < 5.
        assertTrue(b.preTick(SAFE_DEFAULTS,
            tick(3).health(15).totems(0).candidates(withBudget(spot(9, 10, 0), 8)).build()).isEmpty());
        // The pop resets the baseline: it is not "20, still 5 short of used up" any more, but a fresh one at
        // health 15 (as tracked going into this tick), so FIVE needs its own drop of 4 again from there, not
        // just no further pop. No further pop, and 15 - 10 = 5 >= 4: now it confirms. 10 - 0 - 2 = 8 >= 5.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(10).totems(0).candidates(withBudget(spot(9, 10, 0), 2)).build()));
    }

    @Test
    void aHealthRiseInvalidatesThatTicksConfirmation() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).totems(1).crystals(FIVE).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).totems(1).build()).isEmpty());
        b.selfHurt(5);
        var probe = withBudget(spot(9, 10, 0), 8);

        // A pop holds this tick's much-too-generous drop back regardless (health 5, drop 15).
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(5).totems(0).candidates(probe).build()).isEmpty());
        // Health rises 5 -> 16 since the last pre-tick tracked: even though 20 - 16 = 4 is exactly enough on its
        // own, the rise makes this tick's drop impossible to attribute, so FIVE stays counted. 16 - 5 - 8 = 3 < 5.
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(4).health(16).totems(0).candidates(probe).build()).isEmpty());
    }

    // Budget off

    @Test
    void withTheBudgetOffNothingIsTracked() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(METEOR, tick(1).health(20).crystals(FIVE).build()).isEmpty());
        assertTrue(b.preTick(METEOR, tick(2).health(20).build()).isEmpty());
        b.selfHurt(5);
        // Turned on only now: with no baseline tracked while it was off, the packet sent during that gap is
        // dropped, so FIVE keeps its full window even though health already dropped far more than enough.
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(10).candidates(withBudget(spot(9, 10, 0), 2)).build()).isEmpty());
    }
}
