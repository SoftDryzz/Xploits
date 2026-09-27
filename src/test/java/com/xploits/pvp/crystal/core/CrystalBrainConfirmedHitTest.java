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
 * Task R3-15, fix round 2 (rereview-r3-15.md), extended fix round 3 (rereview2-r3-15.md) for absorption: once a
 * full-hit self-damage packet naming a crystal has been handed over ({@link CrystalBrain#selfHurt}), and a
 * health-update packet has been handed over ({@link CrystalBrain#healthUpdateReceived}) <em>after</em> it in the
 * order they arrived — and, only if absorption was present when the packet arrived, an absorption-tracker update
 * ({@link CrystalBrain#absorptionUpdateReceived}) after it too, in either order relative to the health one — that
 * crystal's hit is certain to already be inside health (causal ordering, verified against the MC 1.21.11 yarn jar
 * in the fix round 2 and round 3 reports), and it leaves I for good. No health value, drop, baseline or fraction
 * is read anywhere any more: only relative order. Health is fixed at 20 throughout, since it plays no role in
 * confirmation, only in whether a probe spot's budget passes once a crystal is (or is not) excluded.
 *
 * <p>Reserve R = 5 ({@link Crystals#SAFE_DEFAULTS}). {@link #FIVE}, {@link #SIX} and {@link #SEVEN} each have
 * {@code budgetSelfDamage} 5, so with health 20 a probe spot's {@code budgetSelfDamage} X passes exactly when
 * {@code 20 - C - X >= 5}, i.e. {@code C <= 15 - X}, where C is 5 per still-counted crystal. Every crystal is
 * placed out of break range, so ++ never attacks it itself.
 */
class CrystalBrainConfirmedHitTest {
    private static final CrystalSeen FIVE = outOfBreakRange(crystal(5, 8, 5));
    private static final CrystalSeen SIX = outOfBreakRange(crystal(6, 8, 5));
    private static final CrystalSeen SEVEN = outOfBreakRange(crystal(7, 8, 5));

    private static void assertDecision(Decision expected, List<Action> actions) {
        assertEquals(expected, only(actions).decision());
    }

    /**
     * A probe spot whose {@code budgetSelfDamage} passes exactly when at most {@code maxRemaining} crystals (5
     * each) are still counted in C: {@code 20 - 5 * maxRemaining - X = 5} at the boundary, so
     * {@code X = 15 - 5 * maxRemaining}. Passing proves C is at most {@code 5 * maxRemaining}; refusing proves it
     * is more. {@code refusedUnless(0)} (X = 15) proves full confirmation on its own, whatever the scenario's
     * total crystal count, since it only ever passes at C = 0.
     */
    private static Candidate refusedUnless(int maxRemaining) {
        return withBudget(spot(9, 10, 0), 15 - 5.0 * maxRemaining);
    }

    /** Crystal(s) present at pre-tick 1, gone by pre-tick 2 (health fixed at 20 throughout). */
    private static CrystalBrain vanished(CrystalSeen... crystals) {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(crystals).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).build()).isEmpty());
        return b;
    }

    /** FIVE and SIX present, then gone one pre-tick apart (2, 3); neither packet sent yet. */
    private static CrystalBrain twoStaggered() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(FIVE, SIX).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).crystals(SIX).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).build()).isEmpty());
        return b;
    }

    /** FIVE, SIX and SEVEN present, then gone one pre-tick apart (2, 3, 4); no packets sent yet. */
    private static CrystalBrain threeStaggered() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(FIVE, SIX, SEVEN).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).crystals(SIX, SEVEN).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).crystals(SEVEN).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(4).health(20).build()).isEmpty());
        return b;
    }

    // The core rule: causal order, not value

    @Test
    void xConfirmedOnlyAfterAHealthUpdateSequencedAfterItsPacketIsApplied() {
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, false);
        b.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));
    }

    @Test
    void receivedButNeverFollowedByAnAppliedUpdateKeepsItCounted() {
        // No number of pre-ticks changes this: there is no timeout here at all, only order.
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, false);
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()).isEmpty());
    }

    @Test
    void noPacketAtAllKeepsItCountedEvenWithHealthUpdatesArriving() {
        CrystalBrain b = vanished(FIVE);
        b.healthUpdateReceived();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
    }

    @Test
    void aHealthUpdateBeforeXsPacketKeepsItCountedUntilOneGenuinelyFollows() {
        CrystalBrain b = vanished(FIVE);
        b.healthUpdateReceived();
        b.selfHurt(5, false);
        // The update came first: it could not have reflected a hit that had not even been dealt to us yet.
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
        // A later update, genuinely after the packet, does confirm it.
        b.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()));
    }

    // rereview-r3-15.md's regression: unrelated damage no longer confirms anything early

    @Test
    void anUnrelatedSwordHitBetweenXsPacketAndTheUpdateChangesNothing() {
        // rereview-r3-15.md's hand trace: FIVE's packet, then an enemy's sword hit (a full hit by the same
        // vanilla rule, research-external M1, but its source id is not a crystal we know), then an update. With
        // drop attribution this update's drop could have been entirely the sword hit; with causal ordering the
        // sword hit is simply never matched to anything, so it changes nothing either way.
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, false);
        b.selfHurt(54321, false); // the enemy player's entity id: not a known crystal
        b.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));
    }

    @Test
    void aKnownDifferentCrystalsHitInBetweenChangesNothingForXsOwnConfirmation() {
        // A different, known (foreign) crystal's own full-hit event, occurring between X's packet and the update
        // that confirms X, forms its own mark and does not interfere with X's: both end up confirmed, each on the
        // strength of the same later update following its own packet.
        CrystalBrain b = vanished(FIVE, SIX);
        b.selfHurt(5, false);
        b.selfHurt(6, false);
        b.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));
    }

    // Several crystals, each confirmed by the first applied update after their own packet

    @Test
    void severalCrystalsAreEachConfirmedByTheFirstAppliedUpdateAfterTheirOwnPacket() {
        // The first update, after only FIVE's packet, confirms FIVE alone: C = 10 (SIX, SEVEN still pending), so
        // a spot needing at most 2 still-counted crystals (refusedUnless(2)) passes.
        CrystalBrain partial = threeStaggered();
        partial.selfHurt(5, false);
        partial.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            partial.preTick(SAFE_DEFAULTS, tick(5).health(20).candidates(refusedUnless(2)).build()));
        // Not over-confirmed: SIX and SEVEN's packets have not even arrived yet, so a spot needing at most 1
        // still-counted crystal (refusedUnless(1)) still refuses (true C = 10, more than 5 * 1).
        CrystalBrain partialStrict = threeStaggered();
        partialStrict.selfHurt(5, false);
        partialStrict.healthUpdateReceived();
        assertTrue(partialStrict.preTick(SAFE_DEFAULTS, tick(5).health(20).candidates(refusedUnless(1)).build()).isEmpty());

        // A second update, after SIX's and SEVEN's packets both arrived (in the same batch), confirms them
        // together: all three now excluded.
        CrystalBrain full = threeStaggered();
        full.selfHurt(5, false);
        full.healthUpdateReceived();
        full.selfHurt(6, false);
        full.selfHurt(7, false);
        full.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            full.preTick(SAFE_DEFAULTS, tick(5).health(20).candidates(refusedUnless(0)).build()));
    }

    // review-r3-15.md's original regression: crystals staggered a pre-tick apart

    @Test
    void staggeredCrystalsBothConfirmedByOneLaterUpdateAfterBothPackets() {
        CrystalBrain b = twoStaggered();
        b.selfHurt(5, false);
        b.selfHurt(6, false);
        b.healthUpdateReceived(); // arrives after both packets: safely confirms both together
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()));
    }

    @Test
    void staggeredCrystalsOnlyConfirmTheOneWhosePacketArrivedBeforeTheUpdate() {
        // Only FIVE confirms: C = 5 (SIX still pending, its packet not even arrived yet at the update). At most 1
        // still-counted crystal is enough (refusedUnless(1)).
        CrystalBrain b = twoStaggered();
        b.selfHurt(5, false);
        b.healthUpdateReceived(); // SIX's packet has not arrived yet
        b.selfHurt(6, false);
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(1)).build()));
        // Not both: refusedUnless(0) (needs C = 0) still refuses, since SIX is still counted.
        CrystalBrain strict = twoStaggered();
        strict.selfHurt(5, false);
        strict.healthUpdateReceived();
        strict.selfHurt(6, false);
        assertTrue(strict.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()).isEmpty());
    }

    // Absorption (fix round 3, rereview2-r3-15.md): CrystalTick.health() is health plus absorption, and
    // absorption syncs on a separate packet, one server tick behind health's for the same hit; a crystal that
    // carried absorption at hit time needs that packet's confirmation too, not only health's.

    @Test
    void absorptionZeroConfirmsOnTheHealthUpdateAloneJustAsBeforeThisFix() {
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, false);
        b.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));
    }

    @Test
    void absorptionPresentIsNotConfirmedByTheHealthUpdateAlone() {
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, true);
        b.healthUpdateReceived();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
    }

    @Test
    void absorptionPresentConfirmsOnceBothHealthAndAbsorptionApplyAfterThePacketInEitherOrder() {
        CrystalBrain healthThenAbsorption = vanished(FIVE);
        healthThenAbsorption.selfHurt(5, true);
        healthThenAbsorption.healthUpdateReceived();
        healthThenAbsorption.absorptionUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            healthThenAbsorption.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));

        CrystalBrain absorptionThenHealth = vanished(SIX);
        absorptionThenHealth.selfHurt(6, true);
        absorptionThenHealth.absorptionUpdateReceived();
        absorptionThenHealth.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            absorptionThenHealth.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));
    }

    @Test
    void anAbsorptionUpdateBeforeXsPacketKeepsItCountedUntilOneGenuinelyFollows() {
        CrystalBrain b = vanished(FIVE);
        b.absorptionUpdateReceived(); // before the packet, same batch: cannot count toward it
        b.selfHurt(5, true);
        b.healthUpdateReceived();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
        // A later absorption update, genuinely after the packet, does complete the confirmation.
        b.absorptionUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()));
    }

    @Test
    void anAbsorptionUpdateForAnotherEntityKeepsXCounted() {
        // readDamagePackets only ever calls absorptionUpdateReceived() for an EntityTrackerUpdateS2CPacket that
        // is both about our own entity id and carries the ABSORPTION_AMOUNT entry; one for a different entity
        // (or for us, carrying some other tracked value) is dropped before it ever reaches the core, which is
        // indistinguishable here from no absorption update having arrived at all yet.
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, true);
        b.healthUpdateReceived();
        // No absorptionUpdateReceived() call: stands in for a tracker update filtered out for being about
        // another entity (or not carrying absorption).
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
    }

    @Test
    void theReviewersTenIntoEightAbsorptionTraceIsSafe() {
        // rereview2-r3-15.md's hand trace: 8 HP of absorption up, X deals 10 damage (2 to raw health, up to 8 to
        // absorption). Verified per-tick ordering: ServerWorld.tick's chunkSource step, which flushes entity-
        // tracker data (including a changed ABSORPTION_AMOUNT), runs before the entities step, where
        // ServerPlayerEntity.playerTick sends the health update — so the health update for a crystal's own hit
        // is sent the same tick, while the absorption sync for that same hit is structurally a full server tick
        // behind. Confirming on the health update alone would credit up to X's entire budgetSelfDamage as
        // phantom headroom; this fix keeps X counted until the absorption sync genuinely follows too.
        CrystalBrain b = vanished(FIVE);
        b.selfHurt(5, true); // absorption (8 HP) present when the packet arrived
        b.healthUpdateReceived(); // same tick: only the 2 raw HP is reflected here
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()).isEmpty());
        // One tick later, the absorption sync (the missing 8 HP) finally arrives: only now does X confirm.
        b.absorptionUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()));
    }

    // Foreign crystals, on the same terms

    @Test
    void aForeignCrystalGoingThroughSelfHurtIsConfirmedOnTheSameTerms() {
        // A crystal we never placed (no pending placement when it appeared) is foreign: breakAllowed uses
        // Meteor's rules only for it (Verdict.FOREIGN, skipping the budget's own health read), proving
        // Known.ours is false; it is still confirmed through selfHurt/healthUpdateReceived on the same terms as
        // one of ours, since neither reads ours at all, the same as targetHurt.
        CrystalBrain b = new CrystalBrain();
        assertDecision(Decision.breakCrystal(5, Reason.FOREIGN_CRYSTAL),
            b.preTick(SAFE_DEFAULTS, tick(1).health(20).crystals(crystal(5, 8, 5)).build()));
        b.attackSent();
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(2).health(20).build()).isEmpty());
        b.selfHurt(5, false);
        b.healthUpdateReceived();
        assertDecision(Decision.place(9, Reason.WITHIN_BUDGET),
            b.preTick(SAFE_DEFAULTS, tick(3).health(20).candidates(refusedUnless(0)).build()));
    }

    // Budget off

    @Test
    void withTheBudgetOffNothingIsTracked() {
        CrystalBrain b = new CrystalBrain();
        assertTrue(b.preTick(METEOR, tick(1).health(20).crystals(FIVE).build()).isEmpty());
        assertTrue(b.preTick(METEOR, tick(2).health(20).build()).isEmpty());
        b.selfHurt(5, false);
        b.healthUpdateReceived();
        // Still off at the next pre-tick: both events are dropped right here (forgetWindows, not confirmSelfHits).
        assertTrue(b.preTick(METEOR, tick(3).health(20).build()).isEmpty());
        // Turned on only now: nothing survived, so FIVE keeps its full window.
        assertTrue(b.preTick(SAFE_DEFAULTS, tick(4).health(20).candidates(refusedUnless(0)).build()).isEmpty());
    }
}
