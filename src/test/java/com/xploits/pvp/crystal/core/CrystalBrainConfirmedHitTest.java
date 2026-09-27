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

    // Absorption/regeneration/totem pops invalidate that tick's confirmation, never permanently

    @Test
    void aTotemPopInvalidatesThatTicksConfirmationButNotTheNextOne() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).totems(1).crystals(FIVE).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).totems(1).build()).isEmpty());
        b.selfHurt(5);
        var probe = withBudget(spot(9, 10, 0), 8);

        // Totems 1 -> 0: a pop. The drop (20 - 15 = 5) would otherwise be more than enough (required 4).
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(15).totems(0).candidates(probe).build()).isEmpty());
        // No further pop, same health: now it confirms. 15 - 0 - 8 = 7 >= 5.
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(15).totems(0).candidates(probe).build()));
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
