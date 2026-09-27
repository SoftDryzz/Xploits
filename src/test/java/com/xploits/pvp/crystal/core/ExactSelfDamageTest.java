package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.SelfBudget.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertBreaks;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.SAFE_DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.dealing;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withBudget;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget reads the exact self damage, Meteor's checks keep Meteor's (research-reserve-undershoot):
 * Meteor truncates the raw explosion damage to an int before armour, the server does not, so Meteor's value
 * is up to one raw point short. The numbers are the bench's: netherite with blast protection IV, the spot 4
 * blocks from our feet with nothing in between, Meteor 5.4144 and the server 5.5420.
 */
class ExactSelfDamageTest {
    private static final CrystalSettings BALANCED = DEFAULTS.toBuilder().risk(RiskLevel.BALANCED).build();

    // The raw damage, as the server computes it

    @Test
    void theRawDamageKeepsItsFraction() {
        // n = (1 - 4/12) * 1 = 2/3; (n*n + n) / 2 * 7 * 12 + 1 = 47.667, which Meteor truncates to 47.
        float raw = ExplosionMath.rawDamage(4.0, 1.0);
        assertEquals(47.6667, raw, 1e-3);
        assertNotEquals(47f, raw);
        assertEquals(39.792, ExplosionMath.rawDamage(5.0, 1.0), 1e-3);
    }

    @Test
    void beyondTwelveBlocksThereIsNoDamageAndWithNoExposureOnlyTheOne() {
        assertEquals(0f, ExplosionMath.rawDamage(12.0001, 1.0));
        assertEquals(1f, ExplosionMath.rawDamage(12.0, 1.0), "at 12 blocks the +1 still counts");
        assertEquals(1f, ExplosionMath.rawDamage(4.0, 0.0));
    }

    // The values the records carry

    @Test
    void eachRecordCarriesBothValuesAndTheShortConstructorsGiveTheBudgetMeteors() {
        Candidate p = new Candidate(7, Map.of(), 5.4144, 5.5420, true, Set.of(), false);
        assertEquals(5.4144, p.selfDamage());
        assertEquals(5.5420, p.budgetSelfDamage());
        assertEquals(5.4144, spot(7, 8, 5.4144).budgetSelfDamage());

        CrystalSeen seen = new CrystalSeen(1, 77, Map.of(), 5.4144, 5.5420, 4, true);
        assertEquals(5.4144, seen.selfDamage());
        assertEquals(5.5420, seen.budgetSelfDamage());
        assertEquals(5.4144, crystal(1, 8, 5.4144).budgetSelfDamage());

        CrystalView view = new CrystalView(1, 77, Map.of(), 5.4144, 5.5420, 4, true, true, 0, CrystalView.NEVER, CrystalView.NEVER);
        assertEquals(5.4144, view.selfDamage());
        assertEquals(5.5420, view.budgetSelfDamage());
    }

    @Test
    void aBudgetValueBelowMeteorsIsRaisedToMeteors() {
        // The budget never reads less than Meteor's own prediction.
        assertEquals(5.0, new Candidate(7, Map.of(), 5, 4, true, Set.of(), false).budgetSelfDamage());
        assertEquals(5.0, new CrystalSeen(1, 77, Map.of(), 5, 4, 3, true).budgetSelfDamage());
        assertEquals(5.0, new CrystalView(1, 77, Map.of(), 5, 4, 3, true, true, 0, CrystalView.NEVER, CrystalView.NEVER)
            .budgetSelfDamage());
    }

    @Test
    void anOddBudgetValueIsRefused() {
        for (double odd : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new Candidate(7, Map.of(), 5, odd, true, Set.of(), false));
            assertThrows(IllegalArgumentException.class, () -> new CrystalSeen(1, 77, Map.of(), 5, odd, 3, true));
            assertThrows(IllegalArgumentException.class, () -> new CrystalView(1, 77, Map.of(), 5, odd, 3, true, true, 0,
                CrystalView.NEVER, CrystalView.NEVER));
        }
    }

    // The budget reads the budget value

    @Test
    void aPlacementIsJudgedOnTheExactSelfDamage() {
        // Two crystals exploded: 20 - 2 * 5.5420 = 8.9159. Meteor's value would leave 8.9159 - 5.4144 = 3.5015
        // >= 3.5; the real one leaves 3.3739 < 3.5.
        Candidate exact = withBudget(spot(9, 8, 5.4144), 5.5420);
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(BALANCED, tick(1).health(8.9159).candidates(exact).build()));
        assertTrue(b.holding());
        assertEquals(Decision.none(Reason.OVER_RESERVE), b.lastDecision());

        assertPlaces(9, new CrystalBrain().preTick(BALANCED, tick(1).health(8.9159).candidates(spot(9, 8, 5.4144)).build()));
    }

    @Test
    void breakingOurOwnCrystalIsJudgedOnTheExactSelfDamage() {
        // Meteor 5, exact 5.5, at 7.25: 7.25 - 5 = 2.25 would pass the floor, 7.25 - 5.5 = 1.75 does not.
        CrystalSeen mine = withBudget(crystal(1, 8, 5), 5.5);
        CrystalBrain b = own(mine);
        assertNothing(b.preTick(DEFAULTS, tick(2).health(7.25).crystals(mine).build()));
        assertTrue(b.holding());
        assertEquals(Decision.none(Reason.BELOW_FLOOR), b.lastDecision());

        CrystalSeen meteors = crystal(1, 8, 5);
        assertBreaks(1, own(meteors).preTick(DEFAULTS, tick(2).health(7.25).crystals(meteors).build()));
    }

    @Test
    void fastBreakingOurOwnCrystalIsJudgedOnTheExactSelfDamage() {
        CrystalSeen mine = withBudget(crystal(1, 8, 5), 5.5);
        CrystalBrain b = new CrystalBrain();
        assertPlaces(mine.pos(), b.preTick(DEFAULTS, tick(1).candidates(withBudget(spot(mine.pos(), 8, 5), 5.5)).build()));
        b.placed(mine.pos(), 0);
        // A pre-tick with no rotation, so fast-break may act.
        assertNothing(b.preTick(DEFAULTS, tick(2).build()));
        assertTrue(b.crystalAdded(mine, 7.25, HANDS).isEmpty());

        CrystalSeen meteors = crystal(1, 8, 5);
        CrystalBrain m = new CrystalBrain();
        assertPlaces(meteors.pos(), m.preTick(DEFAULTS, tick(1).candidates(spot(meteors.pos(), 8, 5)).build()));
        m.placed(meteors.pos(), 0);
        assertNothing(m.preTick(DEFAULTS, tick(2).build()));
        assertTrue(m.crystalAdded(meteors, 7.25, HANDS).isPresent());
    }

    @Test
    void aPendingPlacementCountsItsExactSelfDamage() {
        // Placed with Meteor 4 / exact 4.5, still pending. Next spot 2: 11.25 - 4.5 - 2 = 4.75 < Safe's reserve 5;
        // with Meteor's 4 it would be 5.25.
        CrystalBrain b = new CrystalBrain();
        assertPlaces(100, b.preTick(SAFE_DEFAULTS, tick(1).candidates(withBudget(spot(100, 8, 4), 4.5)).build()));
        b.placed(100, 0);
        assertNothing(b.preTick(SAFE_DEFAULTS, tick(2).health(11.25).candidates(spot(200, 8, 2)).build()));
        assertEquals(Decision.none(Reason.OVER_RESERVE), b.lastDecision());

        CrystalBrain m = new CrystalBrain();
        assertPlaces(100, m.preTick(DEFAULTS, tick(1).candidates(spot(100, 8, 4)).build()));
        m.placed(100, 0);
        assertPlaces(200, m.preTick(DEFAULTS, tick(2).health(11.25).candidates(spot(200, 8, 2)).build()));
    }

    @Test
    void standingAndInFlightSumTheExactSelfDamage() {
        CrystalView standing = new CrystalView(1, 1001, Map.of(ENEMY, 8.0), 4, 4.5, 3, true, false, 0,
            CrystalView.NEVER, CrystalView.NEVER);
        CrystalView inFlight = new CrystalView(2, 1002, Map.of(ENEMY, 8.0), 3, 3.25, 3, true, true, 1, 100,
            CrystalView.NEVER);
        SelfBudget budget = SelfBudget.of(101, 20, List.of(standing, inFlight), List.of(), SelfBudget.DEFAULT_RESERVE,
            SelfBudget.DEFAULT_SAFE_SELF_DAMAGE);
        assertEquals(4.5, budget.standing());
        assertEquals(3.25, budget.inFlight());
    }

    @Test
    void breakingACrystalInFlightDoesNotCountItsExactSelfDamageTwice() {
        // In flight at 3.25 exact: I without it is 0, so 5.375 - 0 - 3.25 = 2.125 >= 2. Leaving out Meteor's 3
        // instead would leave 0.25 of it in I: 1.875.
        CrystalView inFlight = new CrystalView(2, 1002, Map.of(ENEMY, 8.0), 3, 3.25, 3, true, true, 1, 100,
            CrystalView.NEVER);
        SelfBudget budget = SelfBudget.of(101, 5.375, List.of(inFlight), List.of(), SelfBudget.DEFAULT_RESERVE,
            SelfBudget.DEFAULT_SAFE_SELF_DAMAGE);
        assertEquals(Verdict.ALLOWED, budget.breakAllowed(inFlight));
    }

    // Meteor's checks keep Meteor's value

    @Test
    void maxDamageReadsMeteorsValueAndTheBudgetThenDecides() {
        // Meteor 5.9 <= max-damage 6, exact 6.1 > 6: Meteor would place it, so it is not filtered.
        Candidate s = withBudget(spot(9, 8, 5.9), 6.1);
        assertPlaces(9, new CrystalBrain().preTick(DEFAULTS, tick(1).candidates(s).build()));
        // At 11 the budget refuses it on the exact value: 11 - 6.1 = 4.9 < Safe's reserve 5 (Meteor's would leave 5.1).
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(SAFE_DEFAULTS, tick(1).health(11).candidates(s).build()));
        assertTrue(b.holding(), "it passed Meteor's checks: the budget refused it");
        assertEquals(Decision.none(Reason.OVER_RESERVE), b.lastDecision());
    }

    @Test
    void withTheBudgetOffTheExactValueChangesNothing() {
        // Anti-suicide on Meteor's 5.9 < 6.05 (the exact 6.1 would be >= 6.05); max-damage on 5.9 <= 6.
        Candidate s = withBudget(spot(9, 8, 5.9), 6.1);
        assertPlaces(9, new CrystalBrain().preTick(METEOR, tick(1).health(6.05).candidates(s).build()));
        CrystalSeen c = withBudget(crystal(1, 8, 5.9), 6.1);
        assertBreaks(1, new CrystalBrain().preTick(METEOR, tick(1).crystals(c).build()));
    }

    /** A brain that placed this crystal at full health and saw it appear (dealing nothing yet) at pre-tick 1. */
    private static CrystalBrain own(CrystalSeen c) {
        CrystalBrain b = new CrystalBrain();
        Candidate s = withBudget(spot(c.pos(), 8, c.selfDamage()), c.budgetSelfDamage());
        assertPlaces(c.pos(), b.preTick(DEFAULTS, tick(1).candidates(s).build()));
        b.placed(c.pos(), 0);
        assertFalse(b.crystalAdded(dealing(c, 0), 20, HANDS).isPresent());
        return b;
    }
}
