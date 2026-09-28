package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.player;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withTotem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B0a (spec Amendment 2026-09-28): the finishing blow. Every scenario starts from
 * {@link #trustedEnemy}, which confirms {@link Crystals#ENEMY}'s health trust the same way a real fight
 * would (a full hit from one of our own crystals, with a measured drop), then reports whatever health the
 * test wants; {@link #LOW_MIN_DAMAGE} lets a test choose the 1.25 margin freely, without {@code min-damage}
 * (6 by default) also gating the same candidate.
 */
class CrystalBrainFinishingBlowTest {
    /** {@link Crystals#DEFAULTS} with {@code min-damage} out of the way, so a test can isolate the margin. */
    private static final CrystalSettings LOW_MIN_DAMAGE = DEFAULTS.toBuilder().minDamage(1).build();

    private static void assertDecision(Decision expected, List<Action> actions) {
        assertEquals(expected, only(actions).decision());
    }

    /**
     * Confirms {@code ENEMY} as trusted: a harmless crystal of ours (base 2000, id 900, deals 6 to ENEMY,
     * self 0) is placed and seen, a full hit from it is handed over, and the next pre-tick reports a real
     * drop (20 to 16: at least half of {@code min(6, 20) = 3}). Trust then stays as it is (sticky) until
     * judged again, so a later pre-tick can report whatever health the test wants.
     *
     * @return the next free pre-tick
     */
    static long trustedEnemy(CrystalBrain b, long t) {
        assertPlaces(2000L, b.preTick(DEFAULTS, tick(t).candidates(spot(2000L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(2000L, 0);
        assertTrue(b.crystalAdded(DEFAULTS, crystal(900, 2000L, Map.of(ENEMY, 6.0), 0.0), 20, HANDS).isEmpty());
        b.targetHurt(ENEMY, 900);
        assertNothing(b.preTick(DEFAULTS, tick(t + 1).targets(player(ENEMY, 3, 16)).build()));
        return t + 2;
    }

    /**
     * The same, then opens ENEMY's hurt window with a second confirming crystal (base 2100, id 901) whose
     * exact raw damage is measured (47.5): the window opens at the pre-tick this returns minus one.
     */
    private static long trustedEnemyWithOpenWindow(CrystalBrain b, long t) {
        long after = trustedEnemy(b, t);
        assertPlaces(2100L, b.preTick(DEFAULTS, tick(after).candidates(spot(2100L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(2100L, 0);
        CrystalSeen withRaw = Crystals.withRaw(crystal(901, 2100L, Map.of(ENEMY, 6.0), 0.0), 47.5);
        assertTrue(b.crystalAdded(withRaw, 20, HANDS).isEmpty());
        b.targetHurt(ENEMY, 901);
        assertNothing(b.preTick(DEFAULTS, tick(after + 1).targets(player(ENEMY, 3, 4)).build()));
        return after + 2;
    }

    /**
     * Places crystal {@code id} at {@code pos} harmlessly (self 0, deals nothing), then confirms it: ours,
     * standing, still dealing nothing. A later pre-tick re-measures it with whatever damage and self damage
     * the test wants (a crystal's own numbers can change tick to tick, from our position).
     */
    private static long ownStandingCrystal(CrystalBrain b, long t, int id, long pos) {
        // Placed with damage 6 (Meteor's own min-damage, DEFAULTS) so the placement itself goes through;
        // once it is ours, what it appears dealing no longer has to meet any minimum.
        assertPlaces(pos, b.preTick(DEFAULTS, tick(t).candidates(spot(pos, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(pos, 0);
        assertTrue(b.crystalAdded(crystal(id, pos, 0.0, 0), 20, HANDS).isEmpty());
        return t + 1;
    }

    // 1. The 1.25 margin

    @Test
    void theMarginIsExactlyOneAndAQuarterTimesTheReportedHealth() {
        // Health 3, reserve 3.5 (Balanced): the ordinary reserve refuses any real self-damage, so only the
        // override (a totem in hand) can place it. Enemy at 4: the margin is exactly 5.
        CrystalBrain atMargin = new CrystalBrain();
        long t = trustedEnemy(atMargin, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), atMargin.preTick(LOW_MIN_DAMAGE,
            tick(t).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 5.0), 1)).build()));

        CrystalBrain justUnder = new CrystalBrain();
        long t2 = trustedEnemy(justUnder, 1);
        assertNothing(justUnder.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, Math.nextDown(5.0)), 1)).build()));
    }

    // 2. Totem in hand (condition a)

    @Test
    void placingThroughTheOverrideNeedsATotemInHand() {
        // Health-paused (5): the cheap pre-check alone already keeps the whole gate shut without a totem.
        CrystalBrain noTotem = new CrystalBrain();
        long t = trustedEnemy(noTotem, 1);
        assertNothing(noTotem.preTick(LOW_MIN_DAMAGE, tick(t).health(3).targets(player(ENEMY, 3, 4))
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        CrystalBrain totem = new CrystalBrain();
        long t2 = trustedEnemy(totem, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), totem.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        // Not paused (6.5, above pause-health): the pre-check never runs, so the tier itself must still refuse
        // an otherwise override-eligible spot (self 100, past max-damage) without a totem in hand.
        CrystalBrain notPausedNoTotem = new CrystalBrain();
        long t3 = trustedEnemy(notPausedNoTotem, 1);
        assertNothing(notPausedNoTotem.preTick(LOW_MIN_DAMAGE, tick(t3).health(6.5).targets(player(ENEMY, 3, 4))
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));
    }

    @Test
    void placingThroughTheOverrideNeverSpendsTheLastTotem() {
        // Owner's decision 2026-09-29: a totem in hand is not enough by itself, the override may never spend
        // the last one; a spare is needed too. Not paused (6.5), so the cheap pre-check never runs: the tier
        // itself must enforce this. Self 100 (past max-damage): only the override could ever place it.
        CrystalBrain oneInHandNoSpare = new CrystalBrain();
        long t = trustedEnemy(oneInHandNoSpare, 1);
        assertNothing(oneInHandNoSpare.preTick(LOW_MIN_DAMAGE, tick(t).health(6.5).hands(withTotem(HANDS, true))
            .totems(1).targets(player(ENEMY, 3, 4)).candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));

        CrystalBrain oneInHandOneSpare = new CrystalBrain();
        long t2 = trustedEnemy(oneInHandOneSpare, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), oneInHandOneSpare.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(6.5).hands(withTotem(HANDS, true)).totems(2).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));

        // Two totems carried, neither in hand: still refused (condition a needs both halves, not the count alone).
        CrystalBrain twoNotInHand = new CrystalBrain();
        long t3 = trustedEnemy(twoNotInHand, 1);
        assertNothing(twoNotInHand.preTick(LOW_MIN_DAMAGE, tick(t3).health(6.5).totems(2)
            .targets(player(ENEMY, 3, 4)).candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));
    }

    @Test
    void breakingThroughTheOverrideAlsoNeedsATotemInHandCheckedFreshEachTime() {
        // Review focus 2: whatever placed the crystal, breaking it through the override is judged again now.
        CrystalBrain withTotemBrain = new CrystalBrain();
        long t = trustedEnemy(withTotemBrain, 1);
        long t2 = ownStandingCrystal(withTotemBrain, t, 950, 2500L);
        assertDecision(Decision.breakCrystal(950, Reason.FINISHING_BLOW), withTotemBrain.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(6.5).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .crystals(crystal(950, 2500L, 6.0, 100)).build()));

        CrystalBrain noTotemBrain = new CrystalBrain();
        long nt = trustedEnemy(noTotemBrain, 1);
        long nt2 = ownStandingCrystal(noTotemBrain, nt, 950, 2500L);
        assertNothing(noTotemBrain.preTick(LOW_MIN_DAMAGE, tick(nt2).health(6.5).targets(player(ENEMY, 3, 4))
            .crystals(crystal(950, 2500L, 6.0, 100)).build()));
    }

    // 3. One at a time (condition b)

    @Test
    void atMostOneOverrideCrystalAtATimePendingThenStanding() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
        b.placed(3000L, 0);

        // Still pending: a second spot cannot go through the override either.
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t + 1).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3001L, Map.of(ENEMY, 6.0), 1)).build()));

        // It appears, standing, ours: still the one override crystal in flight. Re-measured here at 0.5 damage
        // (below min-damage 1, so breakDamage is 0): it neither stops the placing gate by Meteor's own
        // one-at-a-time rule, nor is it itself finishing-grade, so only the placing side's tag is under test.
        CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, 1.0), 3, withTotem(HANDS, true)).isEmpty());
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t + 2).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).crystals(crystal(950, 3000L, 0.5, 1.0))
            .candidates(spot(3002L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    // 4. Condition c: nothing else we can see could take the totem first

    @Test
    void conditionCRefusesWhenSomethingElseCouldAlreadyTakeTheTotem() {
        // Health 6 (above pause-health 5, so this is not also a pause-health case): a foreign standing crystal
        // is the only other threat, and its own self-damage alone decides whether the floor still holds
        // without the new spot. Both leave the ordinary reserve (3.5) unreachable, so both need the override.
        CrystalBrain blocked = new CrystalBrain();
        long t = trustedEnemy(blocked, 1);
        assertNothing(blocked.preTick(LOW_MIN_DAMAGE, tick(t).health(6).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).crystals(crystal(999, 0.0, 4.5))
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        CrystalBrain allowed = new CrystalBrain();
        long t2 = trustedEnemy(allowed, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), allowed.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(6).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .crystals(crystal(999, 0.0, 3.5)).candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    // 5. The hurt-window exclusion

    @Test
    void anOpenHurtWindowOfOursExcludesTheTargetUntilItCloses() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemyWithOpenWindow(b, 1);
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        CrystalBrain later = new CrystalBrain();
        long lt = trustedEnemyWithOpenWindow(later, 1);
        long now = lt;
        // trustedEnemyWithOpenWindow's full hit counts from two pre-ticks before what it returns (the pre-tick
        // right before the one that hands the hit over).
        long opened = lt - 2;
        while (now - opened < TargetWindows.HURT_WINDOW_TICKS) {
            later.preTick(DEFAULTS, tick(now).targets(player(ENEMY, 3, 4)).build());
            now++;
        }
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), later.preTick(LOW_MIN_DAMAGE,
            tick(now).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    // 6. Order: tier 1 (normal budget) beats tier 2 (the override), which beats a larger non-finishing spot

    @Test
    void aNormallyAllowedFinishingSpotBeatsAnOverrideOneEvenWithLessDamage() {
        Candidate normal = spot(3000L, Map.of(ENEMY, 6.0), 1);
        Candidate overrideOnly = spot(3001L, Map.of(ENEMY, 20.0), 100);
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(20).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(normal, overrideOnly).build()));
    }

    @Test
    void anOverrideSpotBeatsALargerNonFinishingSpot() {
        TargetView other = player("other", 3, 20);
        Candidate finishing = spot(3000L, Map.of(ENEMY, 6.0), 100);
        Candidate bigNonFinishing = spot(3001L, Map.of("other", 30.0), 1);
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(6.5).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4), other)
                .candidates(finishing, bigNonFinishing).build()));
    }

    @Test
    void theOverrideOnlyEverServesTheFinishingGradeTarget() {
        // Review focus 1.
        TargetView other = player("other", 3, 20);
        Candidate forEnemy = spot(3000L, Map.of(ENEMY, 6.0), 1);
        Candidate forOther = spot(3001L, Map.of("other", 20.0), 1);

        CrystalBrain both = new CrystalBrain();
        long t = trustedEnemy(both, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), both.preTick(LOW_MIN_DAMAGE,
            tick(t).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4), other)
                .candidates(forEnemy, forOther).build()));

        CrystalBrain onlyOther = new CrystalBrain();
        long t2 = trustedEnemy(onlyOther, 1);
        assertNothing(onlyOther.preTick(LOW_MIN_DAMAGE, tick(t2).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4), other).candidates(forOther).build()));
    }

    // 7. pause-health bypassed only by an override crystal, only with a totem

    @Test
    void pauseHealthIsBypassedOnlyByAnOverrideCrystalAndOnlyWithATotem() {
        CrystalBrain noTotem = new CrystalBrain();
        long t = trustedEnemy(noTotem, 1);
        assertNothing(noTotem.preTick(LOW_MIN_DAMAGE, tick(t).health(4).targets(player(ENEMY, 3, 4))
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        CrystalBrain totem = new CrystalBrain();
        long t2 = trustedEnemy(totem, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), totem.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(4).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        // An ordinary (non-finishing) spot stays paused even with a totem in hand.
        CrystalBrain ordinary = new CrystalBrain();
        assertNothing(ordinary.preTick(LOW_MIN_DAMAGE, tick(1).health(4).hands(withTotem(HANDS, true))
            .candidates(spot(1L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    // 8. Fast-break through the override

    @Test
    void fastBreakGoesThroughTheOverrideToo() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertPlaces(2600L, b.preTick(LOW_MIN_DAMAGE, tick(t).health(20).targets(player(ENEMY, 3, 4))
            .candidates(spot(2600L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(2600L, 0);
        // A pre-tick with nothing to do resets "rotated" (Meteor: fast-break waits for the next pre-tick
        // after one, since a rotation is that tick's one action; line 740).
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t + 1).health(6.5).targets(player(ENEMY, 3, 4)).build()));
        Action a = b.crystalAdded(crystal(960, 2600L, 6.0, 100), 6.5, withTotem(HANDS, true)).orElseThrow();
        assertEquals(Decision.breakCrystal(960, Reason.FINISHING_BLOW), a.decision());
    }

    // 9. Setting off -> identical decisions to today

    @Test
    void withFinishingBlowOffTodaysDecisionIsUnchanged() {
        CrystalSettings off = LOW_MIN_DAMAGE.toBuilder().finishingBlow(false).build();
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertNothing(b.preTick(off, tick(t).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));

        // The normal tier (a finishing-grade spot the ordinary budget already allows) is untouched by the setting.
        CrystalBrain normal = new CrystalBrain();
        long t2 = trustedEnemy(normal, 1);
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), normal.preTick(off,
            tick(t2).health(20).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    // 10. Budget off -> Meteor parity

    @Test
    void withSelfBudgetOffTheDecisionIsMeteorParity() {
        // Self-damage 100 (past max-damage 6): with the budget on, only the override could place it (as in
        // conditionCRefusesWhenSomethingElseCouldAlreadyTakeTheTotem's allowed case, same health); with the
        // budget off there is no override machinery at all, so it is refused exactly as plain Meteor would.
        CrystalSettings on = LOW_MIN_DAMAGE;
        CrystalBrain withBudget = new CrystalBrain();
        long t = trustedEnemy(withBudget, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), withBudget.preTick(on,
            tick(t).health(6.5).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));

        CrystalSettings off = LOW_MIN_DAMAGE.toBuilder().selfBudget(false).build();
        CrystalBrain withoutBudget = new CrystalBrain();
        long t2 = trustedEnemy(withoutBudget, 1);
        assertNothing(withoutBudget.preTick(off, tick(t2).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));
    }

    // Review focus 3: a target reported at exactly 0 health while alive is not finishing-grade without trust

    @Test
    void zeroReportedHealthIsNotFinishingGradeWithoutTrust() {
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(1).health(4).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 0)).candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    // Review focus 4: forgetWindows() must not touch the one-at-a-time tag, early or late

    @Test
    void forgetWindowsNeverAffectsTheOneAtATimeTag() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
        b.placed(3000L, 0);
        // A skipped pre-tick (the adapter's own recovery): windows and hits forgotten, the pending override not.
        b.forgetWindows();
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t + 1).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3001L, Map.of(ENEMY, 6.0), 1)).build()));

        // The pending placement itself expires (Q2): fix round 1, the tag now stays blocked through the late
        // window too (the crystal may still appear and detonate near us), so this alone does not free it.
        long expiredAt = t + CrystalBrain.PENDING_MIN_TICKS;
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(expiredAt).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3002L, Map.of(ENEMY, 6.0), 1)).build()));
        // A second skipped pre-tick, now that it is late rather than pending: still does not touch the tag.
        b.forgetWindows();
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(expiredAt + 1).health(3).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3003L, Map.of(ENEMY, 6.0), 1)).build()));

        // Only once the late window itself elapses with nothing ever appearing does the tag free up.
        for (long idle = expiredAt + 2; idle < expiredAt + CrystalBrain.LATE_OWN_WINDOW; idle++) {
            b.preTick(LOW_MIN_DAMAGE, tick(idle).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4)).build());
        }
        assertDecision(Decision.place(3004L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(expiredAt + CrystalBrain.LATE_OWN_WINDOW).health(3).hands(withTotem(HANDS, true))
                .targets(player(ENEMY, 3, 4)).candidates(spot(3004L, Map.of(ENEMY, 6.0), 1)).build()));
    }

    /**
     * The literal scenario the review found (task B0a review round 1, Important): an override placement's
     * pending window expires (Q2) without its crystal appearing, then the crystal appears late (lag or a high
     * ping) — a second override must stay refused throughout, and only once the first crystal is gone and the
     * disappearance window has passed does the slot free.
     */
    @Test
    void aLateArrivingOverrideCrystalKeepsBlockingASecondOneUntilItIsGoneAndTheDisappearanceWindowPasses() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(6.5).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 100)).build()));
        b.placed(3000L, 0); // lifetime max(5, 0 + 2) = 5

        // The pending placement expires (Q2) with its crystal never appearing yet: a second override refused.
        long expiredAt = t + CrystalBrain.PENDING_MIN_TICKS;
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(expiredAt).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3001L, Map.of(ENEMY, 6.0), 100)).build()));

        // Still within the late window (20 ticks): a third spot stays refused too.
        long appearsAt = expiredAt + 5;
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(appearsAt).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3002L, Map.of(ENEMY, 6.0), 100)).build()));

        // It now appears, late: still ours enough to keep blocking (fix round 1), even though Q2 treats it as
        // foreign in every other way (fast-break disabled here so that is not itself what refuses the 4th spot).
        CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 4.0, 100), 6.5, withTotem(HANDS, true)).isEmpty());
        assertEquals(1, b.lateOwnCrystals());
        assertEquals(Set.of(950), b.finishingCrystalIds());

        // Known now (not late any more): a fourth spot still refused, via Known.overrideMark this time.
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(appearsAt + 1).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).crystals(crystal(950, 3000L, 4.0, 100))
            .candidates(spot(3003L, Map.of(ENEMY, 6.0), 100)).build()));

        // Removed; three more pre-ticks (the disappearance window) still refuse, the fourth finally succeeds.
        b.crystalRemoved(950);
        long removedAt = appearsAt + 2;
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(removedAt).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3004L, Map.of(ENEMY, 6.0), 100)).build()));
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(removedAt + 1).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3005L, Map.of(ENEMY, 6.0), 100)).build()));
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(removedAt + 2).health(6.5).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).candidates(spot(3006L, Map.of(ENEMY, 6.0), 100)).build()));
        assertDecision(Decision.place(3007L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(removedAt + 3).health(6.5).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3007L, Map.of(ENEMY, 6.0), 100)).build()));
    }

    // Review focus 5: the override crystals' ids are readable

    @Test
    void finishingCrystalIdsExposesTheOverridePlacedCrystal() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertTrue(b.finishingCrystalIds().isEmpty());
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(3).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .candidates(spot(3000L, Map.of(ENEMY, 6.0), 1)).build()));
        b.placed(3000L, 0);
        CrystalSettings noFastBreak = LOW_MIN_DAMAGE.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(980, 3000L, 6.0, 0.0), 3, withTotem(HANDS, true)).isEmpty());
        assertEquals(Set.of(980), b.finishingCrystalIds());
    }

    // Task F1 (owner's decision 2026-09-29): the near-death stall task-f1-report.md diagnosed in the
    // capp-balanced-near-death bench run, reproduced here as a pure test with the run's own exact numbers,
    // then unstuck by the finishing-blow override (or, when it cannot take it, by the gate/budget fixes in
    // CrystalBrain.placeGateOpen and SelfBudget.placeAllowed — see CrystalBrainBudgetTest and
    // CrystalSettingsCoverageTest for those two pieces in isolation).

    /** Meteor's own predicted self damage for the run's one crystal, all 3 runs, byte-identical. */
    private static final double F1_METEOR_SELF = 5.41439962387085;
    /** The budget's exact self damage for the same crystal (never below Meteor's own, {@link Damage#budgetSelf}). */
    private static final double F1_BUDGET_SELF = 5.542044639587402;

    /**
     * Places the exact crystal the bench run measured: accepted at high health (20, {@code C = 0}, comfortably
     * WITHIN_BUDGET) and only confirmed standing once health has already dropped to 6 — the run's own
     * timeline (an ordinary health drop between the placement decision and the crystal landing; F1's own
     * report ruled out any self-damage measurement drift between the two). No totem in hand at the moment it
     * appears, matching the run exactly: fast-break tries and is refused, BELOW_FLOOR
     * (6 - 0 - 5.542044639587402 = 0.457955360412598 < FLOOR 2.0), the same verdict the run's own log
     * recorded. Deals 20 to ENEMY (finishing-grade once ENEMY is trusted and reported at 4 or less, margin
     * 1.25 x 4 = 5): this is the run's own crystal, the one that would end the fight if only it could be
     * broken.
     *
     * @return the next free pre-tick, with the crystal standing, ours, deadlocked exactly as the run showed it
     */
    private static long placeTheStuckCrystal(CrystalBrain b, long t, long pos, int id) {
        assertDecision(Decision.place(pos, Reason.WITHIN_BUDGET), b.preTick(LOW_MIN_DAMAGE,
            tick(t).health(20).targets(player(ENEMY, 3, 20))
                .candidates(Crystals.withBudget(spot(pos, 20.0, F1_METEOR_SELF), F1_BUDGET_SELF)).build()));
        b.placed(pos, 0);
        CrystalSeen stuck = Crystals.withBudget(crystal(id, pos, 20.0, F1_METEOR_SELF), F1_BUDGET_SELF);
        assertTrue(b.crystalAdded(stuck, 6, HANDS).isEmpty());
        return t + 1;
    }

    private static CrystalSeen stuckCrystal(int id, long pos) {
        return Crystals.withBudget(crystal(id, pos, 20.0, F1_METEOR_SELF), F1_BUDGET_SELF);
    }

    @Test
    void f1TheStuckCrystalIsBrokenByTheOverrideWhenATotemBacksItAndTheTargetIsTrusted() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        long t2 = placeTheStuckCrystal(b, t, 9000L, 9500);

        // Trusted, finishing-grade (20 >= 1.25 x 4), a totem in hand plus a spare (condition a), nothing else
        // in flight (condition b) and the floor holding without this crystal's own share (condition c, since
        // it is the only crystal in the world): the override breaks it. Ordinary tier 1 still refuses first
        // (BELOW_FLOOR, the same 0.457955360412598 < FLOOR as fast-break already found) — only tier 2 succeeds.
        assertDecision(Decision.breakCrystal(9500, Reason.FINISHING_BLOW), b.preTick(LOW_MIN_DAMAGE,
            tick(t2).health(6).hands(withTotem(HANDS, true)).targets(player(ENEMY, 3, 4))
                .crystals(stuckCrystal(9500, 9000L)).build()));
    }

    @Test
    void f1TheGateStaysShutWhileTheOverrideWouldStillTakeIt() {
        // The mirror image of the two "no" cases below: everything the override needs still holds, but
        // breaking is paused this tick (so breakBest cannot act on it, and it never becomes "waiting") —
        // the gate must keep waiting on it exactly as it always did, since we really would break it given the
        // chance. Without this, the "yes" test above alone cannot tell the difference: once the override
        // actually breaks a crystal, that same crystal is "waiting" (breakDamage 0) by the time placeGateOpen
        // runs later in the very same tick, so its own gate decision that tick is never actually exercised.
        CrystalSettings pausedBreak = LOW_MIN_DAMAGE.toBuilder().pauseOnUse(CrystalSettings.PauseMode.BREAK).build();
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        long t2 = placeTheStuckCrystal(b, t, 9010L, 9510);

        assertNothing(b.preTick(pausedBreak, tick(t2).health(6).usingItem().hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).crystals(stuckCrystal(9510, 9010L))
            .candidates(spot(9011L, 6.0, 0)).build()));
    }

    @Test
    void f1WithOnlyOneTotemTheOverrideRefusesButTheGateOpensAndAHarmlessSpotIsPlaced() {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        long t2 = placeTheStuckCrystal(b, t, 9001L, 9501);
        CrystalSeen stuck = stuckCrystal(9501, 9001L);

        // Trusted and finishing-grade, but only one totem (no spare): condition a refuses the override, the
        // same as the ordinary budget already did. Nothing happens this tick — exactly the bench run's own
        // stall, still reproduced here.
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t2).health(6).hands(withTotem(HANDS, true)).totems(1)
            .targets(player(ENEMY, 3, 4)).crystals(stuck).build()));

        // What ++ does next (owner's decision 2026-09-29, piece a + b): the gate no longer waits forever on a
        // crystal neither the budget nor the override will ever break here, and a genuinely harmless new spot
        // (self exactly 0) is placed even though the stuck crystal alone already leaves less than the reserve
        // AND the floor (6 - 5.542044639587402 = 0.457955360412598, under both 3.5 and 2.0) — no totem needed
        // for this one, since it costs us nothing by construction.
        assertDecision(Decision.place(9002L, Reason.SAFE_SELF_DAMAGE), b.preTick(LOW_MIN_DAMAGE,
            tick(t2 + 1).health(6).targets(player(ENEMY, 3, 4)).crystals(stuck)
                .candidates(spot(9002L, 6.0, 0)).build()));
    }

    @Test
    void f1WhenTheTargetIsNotTrustedTheOverrideRefusesButTheGateOpensAndAHarmlessSpotIsPlaced() {
        // No trustedEnemy() at all here: ENEMY's reported health is never confirmed.
        CrystalBrain b = new CrystalBrain();
        long t2 = placeTheStuckCrystal(b, 1, 9003L, 9503);
        CrystalSeen stuck = stuckCrystal(9503, 9003L);

        // A totem plus a spare is not enough by itself: untrusted, this crystal is never finishing-grade, so
        // neither tier of the override is even reached — the same stall as the run.
        assertNothing(b.preTick(LOW_MIN_DAMAGE, tick(t2).health(6).hands(withTotem(HANDS, true))
            .targets(player(ENEMY, 3, 4)).crystals(stuck).build()));

        // What ++ does next: the same as the other "no" case — the gate opens, and a harmless new spot goes
        // through despite the stuck crystal alone already blowing the reserve and the floor.
        assertDecision(Decision.place(9004L, Reason.SAFE_SELF_DAMAGE), b.preTick(LOW_MIN_DAMAGE,
            tick(t2 + 1).health(6).targets(player(ENEMY, 3, 4)).crystals(stuck)
                .candidates(spot(9004L, 6.0, 0)).build()));
    }
}
