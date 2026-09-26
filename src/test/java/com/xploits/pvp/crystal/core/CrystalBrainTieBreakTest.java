package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withBudget;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task R3-2 (spec §2a): exactly equal target damage breaks toward the crystal that hurts us least, only
 * with the self-budget on; the budget off keeps Meteor's own order (the first spot found, by
 * {@code BlockIterator}'s axis order). No epsilon window: a spot with less target damage is never
 * preferred, no matter how much safer it is.
 */
class CrystalBrainTieBreakTest {
    private static void assertDecision(Decision expected, java.util.List<Action> actions) {
        assertEquals(expected, only(actions).decision());
    }

    // Place

    @Test
    void placeTiesBreakTowardTheSaferSpotOnlyWithTheBudgetOn() {
        // Same target damage (10.0); Meteor's own prediction is 0 for both, so Meteor's checks never favour
        // either one and only the tie-break (budgetSelfDamage) can decide.
        Candidate first = withBudget(spot(1, 10.0, 0), 5.5);
        Candidate second = withBudget(spot(2, 10.0, 0), 4.1);

        assertDecision(Decision.place(2, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(20).candidates(first, second).build()));
        // Budget off: Meteor's own order, the first one found.
        assertDecision(Decision.place(1, Reason.BUDGET_OFF), new CrystalBrain().preTick(METEOR,
            tick(1).health(20).candidates(first, second).build()));
    }

    @Test
    void moreTargetDamageWinsEvenWithMoreSelfDamageNoEpsilonWindow() {
        Candidate high = withBudget(spot(1, 10.0, 0), 5.5);
        Candidate low = withBudget(spot(2, 9.99, 0), 1.0);

        assertDecision(Decision.place(1, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(20).candidates(high, low).build()));
        assertDecision(Decision.place(1, Reason.BUDGET_OFF), new CrystalBrain().preTick(METEOR,
            tick(1).health(20).candidates(high, low).build()));
    }

    @Test
    void threeTiedSpotsPickTheLeastHurtfulThenKeepTheOriginalOrder() {
        Candidate a = withBudget(spot(1, 10.0, 0), 5);
        Candidate b = withBudget(spot(2, 10.0, 0), 5);
        Candidate c = withBudget(spot(3, 10.0, 0), 3);

        assertDecision(Decision.place(3, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(20).candidates(a, b, c).build()));
        // a and b tie on both target damage and self damage: the first found wins.
        assertDecision(Decision.place(1, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(20).candidates(a, b).build()));
    }

    @Test
    void tieBreakUsesTheExactBudgetSelfDamageNotMeteorsPrediction() {
        // Meteor's own selfDamage ties at 5.0 for both; only the exact budget value differs, so a tie-break
        // that read Meteor's selfDamage would see no difference and keep the first spot.
        Candidate a = withBudget(spot(1, 10.0, 5.0), 5.3);
        Candidate b = withBudget(spot(2, 10.0, 5.0), 5.1);

        assertDecision(Decision.place(2, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(20).candidates(a, b).build()));
    }

    @Test
    void fallbackSkipsATiedPairTheBudgetRefusesForTheNextSaferSpot() {
        // Health 6, reserve 5: the tied top spots (self 5) each leave 6 - 0 - 5 = 1 < 5, over epsilon (0.5),
        // so both are refused. A lower-damage, much safer spot (self 1) leaves 6 - 0 - 1 = 5, allowed.
        Candidate high1 = withBudget(spot(1, 10.0, 0), 5);
        Candidate high2 = withBudget(spot(2, 10.0, 0), 5);
        Candidate safer = withBudget(spot(3, 8.0, 0), 1);

        assertDecision(Decision.place(3, Reason.WITHIN_BUDGET), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(6).candidates(high1, high2, safer).build()));
    }

    // Break

    @Test
    void breakTiesAttackTheSaferCrystalFirstOnlyWithTheBudgetOn() {
        CrystalSeen a = withBudget(crystal(1, 8, 0), 5.5);
        CrystalSeen b = withBudget(crystal(2, 8, 0), 4.1);

        assertDecision(Decision.breakCrystal(2, Reason.FOREIGN_CRYSTAL), new CrystalBrain().preTick(DEFAULTS,
            tick(1).health(20).crystals(a, b).build()));
        assertDecision(Decision.breakCrystal(1, Reason.BUDGET_OFF), new CrystalBrain().preTick(METEOR,
            tick(1).health(20).crystals(a, b).build()));
    }
}
