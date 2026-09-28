package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.SelfBudget.Share;
import com.xploits.pvp.crystal.core.SelfBudget.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-damage budget with the worked numbers of the spec (§1, P2, P3). Every value here is exact
 * in binary, so the boundaries are the real ones.
 */
class SelfBudgetTest {
    private static final long NOW = 100;
    private static final double RESERVE = SelfBudget.DEFAULT_RESERVE;
    private static final double SAFE = SelfBudget.DEFAULT_SAFE_SELF_DAMAGE;
    private static final String ENEMY = "enemy";

    private static CrystalView crystal(int id, double self, boolean ours, int attempts, long attackedTick, long removedTick) {
        return new CrystalView(id, 1000L + id, Map.of(ENEMY, 8.0), self, 3.0, true, ours, attempts, attackedTick, removedTick);
    }

    /** Standing, never attacked. */
    private static CrystalView standing(int id, double self, boolean ours) {
        return crystal(id, self, ours, 0, CrystalView.NEVER, CrystalView.NEVER);
    }

    /** Ours, attacked once at that tick, still standing. */
    private static CrystalView attacked(int id, double self, long at) {
        return crystal(id, self, true, 1, at, CrystalView.NEVER);
    }

    /** Gone at that tick; attacked at {@code attackedAt}, or never. */
    private static CrystalView gone(int id, double self, long attackedAt, long removedAt) {
        int attempts = attackedAt == CrystalView.NEVER ? 0 : 1;
        return crystal(id, self, attackedAt != CrystalView.NEVER, attempts, attackedAt, removedAt);
    }

    private static CrystalView at(CrystalView c, double distance) {
        return new CrystalView(c.id(), c.pos(), c.targetDamage(), c.selfDamage(), c.budgetSelfDamage(), distance,
            c.inBreakRange(), c.ours(),
            c.attempts(), c.attackedTick(), c.removedTick());
    }

    private static SelfBudget budget(double health, List<CrystalView> crystals, Double... pending) {
        return SelfBudget.of(NOW, health, crystals, List.of(pending), RESERVE, SAFE);
    }

    private static Candidate candidate(long pos, double targetDamage, double self) {
        return new Candidate(pos, Map.of(ENEMY, targetDamage), self, true, Set.of(), false);
    }

    @Test
    void theNumbersAreTheSpecs() {
        assertEquals(5.0, SelfBudget.DEFAULT_RESERVE, 0.0);
        assertEquals(2.0, SelfBudget.FLOOR, 0.0);
        assertEquals(0.5, SelfBudget.DEFAULT_SAFE_SELF_DAMAGE, 0.0);
        assertEquals(12.0, SelfBudget.HAZARD_RADIUS, 0.0);
        assertEquals(3, SelfBudget.DISAPPEARANCE_WINDOW);
        assertEquals(5, CrystalView.ATTACK_WAIT_TICKS);
    }

    @Test
    void atHealth12ASelf5PlacementIsAllowed() {
        SelfBudget b = budget(12, List.of());

        assertEquals(0.0, b.worstCase(), 0.0);
        // 12 - 0 - 5 = 7 >= 5
        assertEquals(Verdict.ALLOWED, b.placeAllowed(5));
        assertEquals(Reason.WITHIN_BUDGET, b.placeAllowed(5).reason());
    }

    @Test
    void atHealth9ASelf5PlacementIsRefusedAndTheSelf1CandidateIsChosenInstead() {
        SelfBudget b = budget(9, List.of());
        Candidate best = candidate(1, 10, 5);
        Candidate safer = candidate(2, 7, 1);

        // 9 - 0 - 5 = 4 < 5, and 5 is far from tiny
        assertEquals(Verdict.REFUSED_RESERVE, b.placeAllowed(best.selfDamage()));
        assertEquals(Reason.OVER_RESERVE, b.placeAllowed(best.selfDamage()).reason());
        // 9 - 0 - 1 = 8 >= 5
        assertEquals(Verdict.ALLOWED, b.placeAllowed(safer.selfDamage()));

        // Among the positions that pass, the highest damage wins (§1): the brain's choice, done here by hand.
        Candidate chosen = List.of(best, safer).stream()
            .filter(c -> b.placeAllowed(c.selfDamage()).allowed())
            .max(Comparator.comparingDouble(c -> c.damageTo(List.of(ENEMY))))
            .orElseThrow();
        assertEquals(2, chosen.pos());
    }

    @Test
    void anEnemyCrystalOfSelf4AtHealth12RefusesSelf5ButAllowsSelf1() {
        // Meteor alone would place the self-5 crystal: 5 <= max-damage 6 and 5 < health 12.
        SelfBudget b = budget(12, List.of(standing(7, 4, false)));

        assertEquals(0.0, b.inFlight(), 0.0);
        assertEquals(4.0, b.standing(), 0.0);
        assertEquals(4.0, b.worstCase(), 0.0);
        // 12 - 4 - 5 = 3 < 5
        assertEquals(Verdict.REFUSED_RESERVE, b.placeAllowed(5));
        // 12 - 4 - 1 = 7 >= 5
        assertEquals(Verdict.ALLOWED, b.placeAllowed(1));
    }

    @Test
    void safeModeAllowsATinySelfDamageDownToTheFloorExactly() {
        List<CrystalView> enemy = List.of(standing(7, 5, false));

        // 7.5 - 5 - 0.5 = 2.0: under the reserve, exactly at the floor, self exactly epsilon
        assertEquals(Verdict.ALLOWED_SAFE, budget(7.5, enemy).placeAllowed(0.5));
        assertEquals(Reason.SAFE_SELF_DAMAGE, budget(7.5, enemy).placeAllowed(0.5).reason());
        // 7.25 - 5 - 0.5 = 1.75 < 2.0: tiny, but it breaks the floor
        assertEquals(Verdict.REFUSED_FLOOR, budget(7.25, enemy).placeAllowed(0.5));
        assertEquals(Reason.BELOW_FLOOR, budget(7.25, enemy).placeAllowed(0.5).reason());
        // 7.625 - 5 - 0.625 = 2.0 keeps the floor, but 0.625 > epsilon: not tiny
        assertEquals(Verdict.REFUSED_RESERVE, budget(7.625, enemy).placeAllowed(0.625));
        // 10.5 - 5 - 0.5 = 5.0: the reserve is kept, so it is a normal placement, not a safe one
        assertEquals(Verdict.ALLOWED, budget(10.5, enemy).placeAllowed(0.5));
    }

    @Test
    void epsilonAndTheReserveAreSettings() {
        SelfBudget strict = SelfBudget.of(NOW, 7.5, List.of(standing(7, 5, false)), List.of(), 5, 0.25);
        SelfBudget lowReserve = SelfBudget.of(NOW, 9, List.of(), List.of(), 4, SAFE);

        assertEquals(Verdict.REFUSED_RESERVE, strict.placeAllowed(0.5));
        // 9 - 0 - 5 = 4 >= 4
        assertEquals(Verdict.ALLOWED, lowReserve.placeAllowed(5));
    }

    @Test
    void theReserveIsNeverBelowTheFloor() {
        // A reserve under F would let a placement leave less than F, and then its own crystal could never
        // be broken (P2): the reserve setting's minimum is 2.
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, 20, List.of(), List.of(), 1.75, SAFE));
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, 20, List.of(), List.of(), 0, SAFE));
        SelfBudget atFloor = SelfBudget.of(NOW, 9, List.of(), List.of(), SelfBudget.FLOOR, SAFE);
        // 9 - 0 - 7 = 2 >= 2
        assertEquals(Verdict.ALLOWED, atFloor.placeAllowed(7));
    }

    @Test
    void anAttackedStandingCrystalCountsOnlyInFlight() {
        CrystalView hit = attacked(3, 4, NOW - 1);
        SelfBudget b = budget(13, List.of(hit));

        assertEquals(Share.IN_FLIGHT, SelfBudget.shareOf(hit, NOW));
        assertEquals(4.0, b.inFlight(), 0.0);
        assertEquals(0.0, b.standing(), 0.0);
        assertEquals(4.0, b.worstCase(), 0.0);
        // 13 - 4 - 4 = 5 >= 5; counted twice it would be 13 - 8 - 4 = 1
        assertEquals(Verdict.ALLOWED, b.placeAllowed(4));
    }

    @Test
    void inFlightEndsAtMeteorsFifthPreTickAfterTheAttackAndTheCrystalGoesBackToStanding() {
        CrystalView fourTicksAgo = attacked(3, 4, NOW - 4);
        CrystalView fiveTicksAgo = attacked(3, 4, NOW - 5);
        CrystalView mine = standing(9, 3, true);

        assertTrue(fourTicksAgo.waitingAt(NOW));
        assertFalse(fiveTicksAgo.waitingAt(NOW));
        assertEquals(Share.IN_FLIGHT, SelfBudget.shareOf(fourTicksAgo, NOW));
        assertEquals(Share.STANDING, SelfBudget.shareOf(fiveTicksAgo, NOW));

        SelfBudget during = budget(8.5, List.of(fourTicksAgo, mine));
        SelfBudget after = budget(8.5, List.of(fiveTicksAgo, mine));
        assertEquals(4.0, during.inFlight(), 0.0);
        assertEquals(3.0, during.standing(), 0.0);
        assertEquals(0.0, after.inFlight(), 0.0);
        assertEquals(7.0, after.standing(), 0.0);
        assertEquals(during.worstCase(), after.worstCase(), 0.0);
        // Breaking our own crystal reads only I: 8.5 - 4 - 3 = 1.5 < 2, then 8.5 - 0 - 3 = 5.5
        assertEquals(Verdict.REFUSED_FLOOR, during.breakAllowed(mine));
        assertEquals(Verdict.ALLOWED, after.breakAllowed(mine));
    }

    @Test
    void aCrystalGoneWithoutOurAttackCountsInFlightForThreeTicks() {
        for (long age = 0; age < 3; age++) {
            CrystalView c = gone(4, 6, CrystalView.NEVER, NOW - age);
            assertEquals(Share.IN_FLIGHT, SelfBudget.shareOf(c, NOW), "age " + age);
            assertEquals(6.0, budget(20, List.of(c)).inFlight(), 0.0, "age " + age);
            assertEquals(0.0, budget(20, List.of(c)).standing(), 0.0, "age " + age);
        }
        CrystalView expired = gone(4, 6, CrystalView.NEVER, NOW - 3);
        assertEquals(Share.NOT_COUNTED, SelfBudget.shareOf(expired, NOW));
        assertEquals(0.0, budget(20, List.of(expired)).worstCase(), 0.0);
    }

    @Test
    void anAttackedCrystalStaysInFlightUntilThreeTicksAfterItDisappears() {
        // Its wait ended long ago; the disappearance window is what counts once it is gone.
        assertEquals(Share.IN_FLIGHT, SelfBudget.shareOf(gone(4, 6, NOW - 10, NOW - 2), NOW));
        assertEquals(Share.NOT_COUNTED, SelfBudget.shareOf(gone(4, 6, NOW - 4, NOW - 3), NOW));
    }

    @Test
    void onlyCrystalsWithinTheHazardRadiusCount() {
        CrystalView edge = at(standing(1, 1, false), 12.0);
        CrystalView beyond = at(standing(2, 1, false), 12.5);
        CrystalView goneBeyond = at(gone(3, 1, CrystalView.NEVER, NOW), 12.5);

        assertEquals(Share.STANDING, SelfBudget.shareOf(edge, NOW));
        assertEquals(Share.NOT_COUNTED, SelfBudget.shareOf(beyond, NOW));
        assertEquals(Share.NOT_COUNTED, SelfBudget.shareOf(goneBeyond, NOW));
        assertEquals(1.0, budget(20, List.of(edge, beyond, goneBeyond)).worstCase(), 0.0);
    }

    @Test
    void pendingPlacementsCountAsStanding() {
        SelfBudget b = budget(12, List.of(), 3.0, 1.0);

        assertEquals(4.0, b.standing(), 0.0);
        // 12 - 4 - 5 = 3 < 5
        assertEquals(Verdict.REFUSED_RESERVE, b.placeAllowed(5));
    }

    @Test
    void everyLiveCrystalWithinTheRadiusIsInExactlyOneShareAndTheSumsAddUp() {
        List<CrystalView> all = List.of(
            standing(1, 1, false), standing(2, 2, true), attacked(3, 4, NOW), attacked(4, 8, NOW - 5),
            gone(5, 16, CrystalView.NEVER, NOW - 1), gone(6, 32, NOW - 2, NOW), gone(7, 64, NOW - 9, NOW - 3));
        double in = 0;
        double standing = 0;
        for (CrystalView c : all) {
            Share share = SelfBudget.shareOf(c, NOW);
            if (c.live()) assertTrue(share == Share.IN_FLIGHT || share == Share.STANDING, "crystal " + c.id());
            if (share == Share.IN_FLIGHT) in += c.selfDamage();
            if (share == Share.STANDING) standing += c.selfDamage();
        }
        SelfBudget b = budget(200, all, 128.0);

        assertEquals(4 + 16 + 32, in, 0.0);
        assertEquals(1 + 2 + 8, standing, 0.0);
        assertEquals(in, b.inFlight(), 0.0);
        assertEquals(standing + 128, b.standing(), 0.0);
        assertEquals(b.inFlight() + b.standing(), b.worstCase(), 0.0);
    }

    @Test
    void breakingOurOwnCrystalKeepsTheFloorAgainstWhatIsInFlight() {
        CrystalView mine = standing(1, 5, true);

        // 7 - 0 - 5 = 2 >= 2
        assertEquals(Verdict.ALLOWED, budget(7, List.of(mine)).breakAllowed(mine));
        // 6.75 - 0 - 5 = 1.75 < 2
        assertEquals(Verdict.REFUSED_FLOOR, budget(6.75, List.of(mine)).breakAllowed(mine));
        assertEquals(Reason.BELOW_FLOOR, budget(6.75, List.of(mine)).breakAllowed(mine).reason());
        // Standing crystals are not in I: a foreign self-10 crystal does not stop it
        assertEquals(Verdict.ALLOWED, budget(7, List.of(mine, standing(2, 10, false))).breakAllowed(mine));
        // 8 - 1 - 5 = 2 with another crystal of ours in flight
        assertEquals(Verdict.ALLOWED, budget(8, List.of(mine, attacked(2, 1, NOW))).breakAllowed(mine));
        assertEquals(Verdict.REFUSED_FLOOR, budget(7.5, List.of(mine, attacked(2, 1, NOW))).breakAllowed(mine));
    }

    @Test
    void anOwnCrystalAlreadyInFlightIsNotCountedTwiceWhenBreakingIt() {
        CrystalView mine = attacked(1, 5, NOW - 1);

        // 7 - (5 - 5) - 5 = 2
        assertEquals(Verdict.ALLOWED, budget(7, List.of(mine)).breakAllowed(mine));
    }

    @Test
    void aFreshOwnCrystalNotYetInTheTickIsJudgedAgainstTheCurrentInFlight() {
        // Fast-break (P4): the crystal has just appeared and is not among this tick's crystals.
        CrystalView fresh = standing(9, 3, true);
        SelfBudget b = budget(8, List.of(attacked(2, 4, NOW)));

        // 8 - 4 - 3 = 1 < 2
        assertEquals(Verdict.REFUSED_FLOOR, b.breakAllowed(fresh));
    }

    @Test
    void breakingAForeignCrystalNeverAsksTheBudget() {
        // Health far below anything the own-break rule would accept: 3 - 4 - 5 < 2.
        CrystalView enemy = standing(1, 5, false);
        SelfBudget b = budget(3, List.of(enemy, attacked(2, 4, NOW)));

        assertEquals(Verdict.FOREIGN, b.breakAllowed(enemy));
        assertTrue(b.breakAllowed(enemy).allowed());
        assertEquals(Reason.FOREIGN_CRYSTAL, b.breakAllowed(enemy).reason());
    }

    // worstCaseWithoutCrystal() (task B0a, condition c of the finishing-blow override)

    @Test
    void worstCaseWithoutCrystalSubtractsItsOwnShareWhereverItWasCounted() {
        CrystalView standing = standing(1, 4, true);
        CrystalView inFlight = attacked(2, 6, NOW);
        SelfBudget b = budget(20, List.of(standing, inFlight));

        assertEquals(10.0, b.worstCase(), 0.0);
        assertEquals(6.0, b.worstCaseWithoutCrystal(standing, NOW), 0.0, "standing's own 4 removed");
        assertEquals(4.0, b.worstCaseWithoutCrystal(inFlight, NOW), 0.0, "in-flight's own 6 removed");
    }

    @Test
    void worstCaseWithoutCrystalIsUnchangedForOneNotCountedAtAll() {
        CrystalView beyond = at(standing(1, 4, true), 12.5);
        SelfBudget b = budget(20, List.of(beyond));

        assertEquals(0.0, b.worstCase(), 0.0);
        assertEquals(0.0, b.worstCaseWithoutCrystal(beyond, NOW), 0.0);
    }

    @Test
    void worstCaseWithoutCrystalNeverMutatesTheBudget() {
        CrystalView standing = standing(1, 4, true);
        SelfBudget b = budget(20, List.of(standing));

        b.worstCaseWithoutCrystal(standing, NOW);
        assertEquals(4.0, b.worstCase(), 0.0, "a second, independent reading: the budget itself is untouched");
    }

    @Test
    void aCrystalThatIsGoneCannotBeBroken() {
        CrystalView c = gone(1, 5, CrystalView.NEVER, NOW);

        assertThrows(IllegalArgumentException.class, () -> budget(20, List.of(c)).breakAllowed(c));
    }

    @Test
    void itRejectsWhatCannotBe() {
        List<CrystalView> none = List.of();
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, -1, none, List.of(), RESERVE, SAFE));
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, Double.NaN, none, List.of(), RESERVE, SAFE));
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, 20, none, List.of(-1.0), RESERVE, SAFE));
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, 20, none, List.of(), -1, SAFE));
        assertThrows(IllegalArgumentException.class, () -> SelfBudget.of(NOW, 20, none, List.of(), RESERVE, -0.5));
        assertThrows(IllegalArgumentException.class, () -> budget(20, List.of()).placeAllowed(-1));
        // The same crystal twice, and ticks from the future
        assertThrows(IllegalArgumentException.class, () -> budget(20, List.of(standing(1, 1, false), standing(1, 2, true))));
        assertThrows(IllegalArgumentException.class, () -> budget(20, List.of(attacked(1, 1, NOW + 1))));
        assertThrows(IllegalArgumentException.class, () -> budget(20, List.of(gone(1, 1, CrystalView.NEVER, NOW + 1))));
        assertThrows(IllegalArgumentException.class, () -> budget(20, List.of(gone(1, 1, NOW, NOW - 1))));
    }
}
