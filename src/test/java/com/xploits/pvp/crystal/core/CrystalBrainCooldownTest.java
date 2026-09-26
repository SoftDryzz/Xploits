package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.crystalRaw;
import static com.xploits.pvp.crystal.core.Crystals.dealing;
import static com.xploits.pvp.crystal.core.Crystals.shielding;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Our own hurt cooldown inside the brain (spec, Round 2 (a)): which hits open the window, which actions it
 * credits, what cancels it, and the invariant: the credit only relaxes our own budget, so every action is still
 * one Meteor allows with the same facts. Every number is exact in binary.
 *
 * <p>The fight used throughout: our crystal A (self 5, raw 12) is placed and broken at full health, its damage
 * packet arrives right after that pre-tick, and the next pre-tick shows the health after the hit.
 */
class CrystalBrainCooldownTest {
    private static final long SPOT = 5000;
    /** Our crystal A: 8 to the enemy, 5 to us, raw 12. */
    private static final CrystalSeen A = crystalRaw(1, 8, 5, 12);
    private static final CrystalSettings NO_ROTATE = DEFAULTS.toBuilder().rotate(false).build();

    /** A brain and the next free pre-tick. */
    private record Fight(CrystalBrain brain, long next) {
        /** Pre-ticks with nothing to do, at this health, up to {@code until} (exclusive). */
        long idleUntil(long until, double health) {
            long t = next;
            for (; t < until; t++) assertNothing(brain.preTick(DEFAULTS, tick(t).health(health).build()));
            return t;
        }
    }

    /** Places our crystals at full health; each appears while pending, harmless until measured again. */
    private static long own(CrystalBrain b, long t, CrystalSeen... crystals) {
        List<CrystalSeen> standing = new ArrayList<>();
        for (CrystalSeen c : crystals) {
            assertPlaces(c.pos(), b.preTick(DEFAULTS, tick(t).crystals(standing).candidates(spot(c.pos(), 8, c.selfDamage())).build()));
            b.placed(c.pos(), 0);
            CrystalSeen harmless = dealing(c, 0);
            assertTrue(b.crystalAdded(harmless, 20, HANDS).isEmpty());
            standing.add(harmless);
            t++;
        }
        return t;
    }

    /**
     * Places A and breaks it at full health; A leaves the world and its damage packet (ours: player_explosion,
     * cause us, direct A) arrives before the next pre-tick, with this round trip. {@code hit} false: the same
     * fight without the packet.
     */
    private static Fight afterOurHit(int rtt, boolean hit, CrystalSeen... alsoOurs) {
        CrystalBrain b = new CrystalBrain();
        CrystalSeen[] all = new CrystalSeen[alsoOurs.length + 1];
        all[0] = A;
        System.arraycopy(alsoOurs, 0, all, 1, alsoOurs.length);
        long t = own(b, 1, all);
        List<CrystalSeen> standing = new ArrayList<>(List.of(A));
        for (CrystalSeen c : alsoOurs) standing.add(dealing(c, 0));
        assertEquals(Decision.breakCrystal(A.id(), Reason.WITHIN_BUDGET),
            only(b.preTick(DEFAULTS, tick(t).crystals(standing).build())).decision());
        b.attackSent();
        b.crystalRemoved(A.id());
        if (hit) b.hurtByOwnCrystal(A.id(), rtt);
        return new Fight(b, t + 1);
    }

    private static Fight afterOurHit(int rtt) {
        return afterOurHit(rtt, true);
    }

    private static void assertDecision(Decision expected, List<Action> actions) {
        assertEquals(expected, only(actions).decision());
    }

    // What the credit allows

    @Test
    void aPlacementInsideOurHurtCooldownCountsNoSelfDamage() {
        Fight f = afterOurHit(0);
        // A is still in flight (5): 9 - 5 - 5 < 5 refused; credited 9 - 5 - 0 = 4, above the floor
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            f.brain.preTick(DEFAULTS, tick(f.next).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
        assertEquals(1, f.brain.cooldownCredits());

        // The same fight without the packet: refused.
        Fight without = afterOurHit(0, false);
        assertNothing(without.brain.preTick(DEFAULTS, tick(without.next).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
        assertEquals(Decision.none(Reason.OVER_RESERVE), without.brain.lastDecision());
        assertEquals(0, without.brain.cooldownCredits());

        // And Meteor places it with the same facts.
        assertPlaces(SPOT, new CrystalBrain().preTick(METEOR, tick(1).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    @Test
    void breakingOurOwnCrystalInsideOurHurtCooldownCountsNoSelfDamage() {
        CrystalSeen b2 = crystalRaw(2, 8, 5, 10);
        Fight f = afterOurHit(0, true, b2);
        CrystalSeen harmless = dealing(b2, 0);
        // A stays in flight three pre-ticks; B, harmless until now, does not stop anything.
        long t = f.next;
        for (; t < f.next + 3; t++) assertNothing(f.brain.preTick(DEFAULTS, tick(t).health(6).crystals(harmless).build()));
        // 6 - 0 - 5 = 1 < 2 refused; credited 6 - 0 - 0 = 6
        assertDecision(Decision.breakCrystal(2, Reason.HURT_COOLDOWN),
            f.brain.preTick(DEFAULTS, tick(t).health(6).crystals(b2).build()));

        Fight without = afterOurHit(0, false, b2);
        long u = without.next;
        for (; u < without.next + 3; u++) assertNothing(without.brain.preTick(DEFAULTS, tick(u).health(6).crystals(harmless).build()));
        assertNothing(without.brain.preTick(DEFAULTS, tick(u).health(6).crystals(b2).build()));
        assertEquals(Decision.none(Reason.BELOW_FLOOR), without.brain.lastDecision());
    }

    // Condition 1: only a hit by one of our own crystals opens the window

    @Test
    void aHitByACrystalWeDidNotPlaceOpensNoWindowAndCancelsTheOpenOne() {
        Fight f = afterOurHit(0);
        f.brain.hurtByOwnCrystal(99, 0);
        assertNothing(f.brain.preTick(DEFAULTS, tick(f.next).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    @Test
    void aForeignCrystalWeBrokeOpensNoWindow() {
        // Breaking a crystal we did not place is Meteor's to do, and its hit is caused by us too; but only our
        // own crystals open the window.
        CrystalSeen theirs = crystalRaw(3, 8, 5, 12);
        CrystalBrain b = new CrystalBrain();
        assertEquals(Decision.breakCrystal(3, Reason.FOREIGN_CRYSTAL),
            only(b.preTick(DEFAULTS, tick(1).crystals(theirs).build())).decision());
        b.attackSent();
        b.crystalRemoved(3);
        b.hurtByOwnCrystal(3, 0);
        assertNothing(b.preTick(DEFAULTS, tick(2).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    @Test
    void aHitBeforeTheFirstPreTickOpensNothing() {
        CrystalBrain b = new CrystalBrain();
        b.hurtByOwnCrystal(1, 0);
        b.hurtByOther();
        assertNothing(b.preTick(DEFAULTS, tick(1).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    // Condition 2: counted from the packet, with the full round trip

    @Test
    void theCreditEndsWhenTheServersWindowMayHaveEnded() {
        // The packet came after pre-tick 2 (A broken there): with no latency, credited up to 2 + 7.
        Fight in = afterOurHit(0);
        long t = in.idleUntil(9, 9);
        assertEquals(9, t);
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            in.brain.preTick(DEFAULTS, tick(t).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));

        Fight out = afterOurHit(0);
        long u = out.idleUntil(10, 9);
        // 9 - 0 - 5 = 4 < 5
        assertNothing(out.brain.preTick(DEFAULTS, tick(u).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    @Test
    void aLongerRoundTripShortensTheCredit() {
        // 20 - k > 10 + 4 + 2: credited up to 2 + 3
        Fight in = afterOurHit(4);
        long t = in.idleUntil(5, 9);
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            in.brain.preTick(DEFAULTS, tick(t).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));

        Fight out = afterOurHit(4);
        long u = out.idleUntil(6, 9);
        assertNothing(out.brain.preTick(DEFAULTS, tick(u).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    @Test
    void aFastBreakBetweenPreTicksIsJudgedAtTheNextPreTick() {
        // Round trip 1: credited while 20 - k > 13, so k <= 6 (the packet came after pre-tick 2). The fast-break
        // goes out after pre-tick t, before t + 1: it is judged at t + 1. Rotate off, or the placement's rotation
        // would hold the fast-break back (Meteor's one action per tick).
        CrystalSeen fresh = crystal(7, SPOT, 8, 5, 10);

        Fight early = afterOurHit(1);
        long t = early.idleUntil(7, 6);
        // k = 5 at the placement, 6 at the fast-break
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            early.brain.preTick(NO_ROTATE, tick(t).health(6).candidates(spot(SPOT, 8, 5, 10)).build()));
        early.brain.placed(SPOT, 1);
        assertEquals(Decision.breakCrystal(7, Reason.HURT_COOLDOWN),
            early.brain.crystalAdded(fresh, 6, HANDS).orElseThrow().decision());

        Fight late = afterOurHit(1);
        long u = late.idleUntil(8, 6);
        // k = 6 at the placement, 7 at the fast-break: 6 - 0 - 5 = 1 < 2
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            late.brain.preTick(NO_ROTATE, tick(u).health(6).candidates(spot(SPOT, 8, 5, 10)).build()));
        late.brain.placed(SPOT, 1);
        assertTrue(late.brain.crystalAdded(fresh, 6, HANDS).isEmpty());
    }

    // Condition 3: no shield

    @Test
    void blockingWithAShieldStopsTheCredit() {
        Fight f = afterOurHit(0);
        assertNothing(f.brain.preTick(DEFAULTS, tick(f.next).health(9).hands(shielding(HANDS))
            .candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    // Condition 4: a whole raw point below, unrounded

    @Test
    void aNewRawNotAWholePointBelowTheLastIsNotCredited() {
        Fight in = afterOurHit(0);
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            in.brain.preTick(DEFAULTS, tick(in.next).health(9).candidates(spot(SPOT, 8, 5, 11)).build()));

        Fight out = afterOurHit(0);
        assertNothing(out.brain.preTick(DEFAULTS, tick(out.next).health(9).candidates(spot(SPOT, 8, 5, 11.0625)).build()));
    }

    @Test
    void aSpotWhoseRawIsUnknownIsNotCredited() {
        Fight f = afterOurHit(0);
        assertNothing(f.brain.preTick(DEFAULTS, tick(f.next).health(9).candidates(spot(SPOT, 8, 5)).build()));
    }

    // Condition 5: nothing else hit us

    @Test
    void anotherHitCancelsTheCredit() {
        Fight f = afterOurHit(0);
        f.brain.hurtByOther();
        assertNothing(f.brain.preTick(DEFAULTS, tick(f.next).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    @Test
    void aHealthDropTheHitCannotExplainCancelsTheCredit() {
        // 20 -> 9 right after the packet is the hit; 9 -> 8.5 three pre-ticks later is not.
        Fight f = afterOurHit(0);
        long t = f.idleUntil(f.next + 2, 9);
        assertNothing(f.brain.preTick(DEFAULTS, tick(t).health(8.5).candidates(spot(SPOT, 8, 5, 10)).build()));

        Fight kept = afterOurHit(0);
        long u = kept.idleUntil(kept.next + 2, 9);
        assertDecision(Decision.place(SPOT, Reason.HURT_COOLDOWN),
            kept.brain.preTick(DEFAULTS, tick(u).health(9).candidates(spot(SPOT, 8, 5, 10)).build()));
    }

    // The invariant: Meteor's checks keep the full damage

    @Test
    void theCreditNeverRelaxesMaxDamage() {
        // Self 7 is over max-damage 6: Meteor refuses it, credit or not.
        Fight f = afterOurHit(0);
        long t = f.idleUntil(f.next + 3, 20);
        assertNothing(f.brain.preTick(DEFAULTS, tick(t).health(20).candidates(spot(SPOT, 8, 7, 10)).build()));
        assertEquals(Decision.none(Reason.NOTHING_TO_DO), f.brain.lastDecision());
    }

    @Test
    void theCreditNeverRelaxesAntiSuicide() {
        // Self 6 at health 6: Meteor's anti-suicide refuses it (6 >= 6); the budget with the credit would allow it.
        Fight f = afterOurHit(0);
        long t = f.idleUntil(f.next + 3, 6);
        assertNothing(f.brain.preTick(DEFAULTS, tick(t).health(6).candidates(spot(SPOT, 8, 6, 10)).build()));
        assertEquals(Decision.none(Reason.NOTHING_TO_DO), f.brain.lastDecision());
    }

    @Test
    void theCreditNeverRelaxesMaxDamageWhenBreaking() {
        // B placed as self 1, then we moved: self 7 now. Meteor does not break it; neither does ++.
        CrystalSeen b2 = crystalRaw(2, 8, 1, 10);
        Fight f = afterOurHit(0, true, b2);
        CrystalSeen now = new CrystalSeen(2, b2.pos(), Map.of(Crystals.ENEMY, 8.0), 7, 3, true, 10);
        long t = f.next;
        for (; t < f.next + 3; t++) assertNothing(f.brain.preTick(DEFAULTS, tick(t).health(20).crystals(dealing(b2, 0)).build()));
        assertNothing(f.brain.preTick(DEFAULTS, tick(t).health(20).crystals(now).build()));
    }

    @Test
    void theCreditNeverRelaxesPauseHealth() {
        Fight f = afterOurHit(0);
        assertNothing(f.brain.preTick(DEFAULTS, tick(f.next).health(5).candidates(spot(SPOT, 8, 1, 10)).build()));
    }

    @Test
    void everyCreditedPlacementIsOneMeteorMakesWithTheSameFacts() {
        double[] healths = {5.5, 6, 6.5, 7, 9, 11, 13};
        double[] selves = {0.5, 3, 5, 5.5, 6, 6.5, 7};
        int credited = 0;
        for (double health : healths) {
            for (double self : selves) {
                Fight f = afterOurHit(0);
                List<Action> plus = f.brain.preTick(DEFAULTS, tick(f.next).health(health).candidates(spot(SPOT, 8, self, 10)).build());
                if (plus.isEmpty()) continue;
                if (plus.get(0).decision().reason() == Reason.HURT_COOLDOWN) credited++;
                List<Action> meteor = new CrystalBrain().preTick(METEOR, tick(1).health(health).candidates(spot(SPOT, 8, self, 10)).build());
                assertEquals(1, meteor.size(), "health " + health + ", self " + self);
                assertEquals(SPOT, meteor.get(0).decision().ref());
            }
        }
        assertTrue(credited > 0);
    }
}
