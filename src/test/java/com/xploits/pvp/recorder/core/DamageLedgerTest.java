package com.xploits.pvp.recorder.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.CombatEvent.SelfDamaged;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every number here is worked out by hand from the health values fed in. */
class DamageLedgerTest {
    private static final SelfDamaged CRYSTAL_BY_FOO = new SelfDamaged(DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", false);
    private static final SelfDamaged CRYSTAL_BY_BAR = new SelfDamaged(DamageKind.CRYSTAL, AttackerKind.PLAYER, "Bar", false);
    private static final SelfDamaged MELEE_BY_FOO = new SelfDamaged(DamageKind.MELEE, AttackerKind.PLAYER, "Foo", false);
    private static final SelfDamaged OWN_CRYSTAL = new SelfDamaged(DamageKind.CRYSTAL, AttackerKind.SELF, null, false);

    private static DamageEvent hit(long tick, SelfDamaged by, double before, double after) {
        return new DamageEvent(tick, by.kind(), by.by(), by.attacker(), before, after, false);
    }

    private static DamageEvent lethalHit(long tick, SelfDamaged by, double before) {
        return new DamageEvent(tick, by.kind(), by.by(), by.attacker(), before, 0, true);
    }

    private static DamageEvent unseen(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.UNSEEN, AttackerKind.NONE, null, before, after, false);
    }

    private static DamageLedger ledger(double health) {
        DamageLedger ledger = new DamageLedger();
        ledger.start(health);
        return ledger;
    }

    @Test
    void oneHitTakesTheWholeDrop() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        assertEquals(List.of(hit(100, CRYSTAL_BY_FOO, 20, 9.5)), ledger.observe(100, 9.5, false));
    }

    @Test
    void aDropThreeTicksAfterTheHitIsStillItsOwn() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        assertEquals(List.of(), ledger.observe(101, 20, false));
        assertEquals(List.of(), ledger.observe(102, 20, false));
        assertEquals(List.of(hit(100, CRYSTAL_BY_FOO, 20, 14)), ledger.observe(103, 14, false));
    }

    @Test
    void aDropFourTicksAfterTheHitIsNotAndTheHitIsDiscarded() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        for (long t = 101; t <= 103; t++) assertEquals(List.of(), ledger.observe(t, 20, false));
        // No hit was ever matched, so this is not in any invulnerability window: ignored.
        assertEquals(List.of(), ledger.observe(104, 14, false));
    }

    @Test
    void aDropIsSplitEvenlyInTheOrderTheHitsCame() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        ledger.noteHit(101, CRYSTAL_BY_BAR);
        // 20 - 8 = 12 between two hits: 6 each.
        assertEquals(List.of(hit(100, CRYSTAL_BY_FOO, 20, 14), hit(101, CRYSTAL_BY_BAR, 14, 8)),
            ledger.observe(101, 8, false));
    }

    @Test
    void threeHitsShareADropInThirds() {
        DamageLedger ledger = ledger(19);
        ledger.noteHit(50, CRYSTAL_BY_FOO);
        ledger.noteHit(50, OWN_CRYSTAL);
        ledger.noteHit(50, MELEE_BY_FOO);
        // 19 - 10 = 9: 3 each.
        assertEquals(List.of(hit(50, CRYSTAL_BY_FOO, 19, 16), hit(50, OWN_CRYSTAL, 16, 13), hit(50, MELEE_BY_FOO, 13, 10)),
            ledger.observe(51, 10, false));
    }

    @Test
    void aDropWithNoPacketNineTicksAfterAHitIsUnseen() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        assertEquals(List.of(hit(100, CRYSTAL_BY_FOO, 20, 14)), ledger.observe(100, 14, false));
        assertEquals(List.of(unseen(109, 14, 10)), ledger.observe(109, 10, false));
    }

    @Test
    void theWindowStillHoldsTenTicksAfterTheHit() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        ledger.observe(100, 14, false);
        assertEquals(List.of(unseen(110, 14, 10)), ledger.observe(110, 10, false));
    }

    @Test
    void aDropWithNoPacketElevenTicksAfterIsIgnoredAndMovesTheBaseline() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        ledger.observe(100, 14, false);
        assertEquals(List.of(), ledger.observe(111, 10, false));
        // The ignored drop still moved the baseline: the next hit counts from 10, not from 14.
        ledger.noteHit(120, MELEE_BY_FOO);
        assertEquals(List.of(hit(120, MELEE_BY_FOO, 10, 6)), ledger.observe(120, 6, false));
    }

    @Test
    void anUnseenDropDoesNotStretchTheWindow() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        ledger.observe(100, 14, false);
        assertEquals(List.of(unseen(108, 14, 12)), ledger.observe(108, 12, false));
        // Counted from the real hit at 100, not from the unseen drop at 108.
        assertEquals(List.of(), ledger.observe(112, 10, false));
    }

    @Test
    void healingIsIgnoredAndRaisesTheBaseline() {
        DamageLedger ledger = ledger(10);
        assertEquals(List.of(), ledger.observe(5, 14, false));
        ledger.noteHit(6, MELEE_BY_FOO);
        assertEquals(List.of(hit(6, MELEE_BY_FOO, 14, 12)), ledger.observe(6, 12, false));
    }

    @Test
    void aDropBelowTheNoiseIsNotAHit() {
        DamageLedger ledger = ledger(16);
        ledger.noteHit(1, MELEE_BY_FOO);
        assertEquals(List.of(), ledger.observe(1, 15.96875, false));
    }

    @Test
    void aDropJustAboveTheNoiseIsAHit() {
        DamageLedger ledger = ledger(16);
        ledger.noteHit(1, MELEE_BY_FOO);
        assertEquals(List.of(hit(1, MELEE_BY_FOO, 16, 15.9375)), ledger.observe(1, 15.9375, false));
    }

    @Test
    void onAPopThePendingHitsAreLethalAndShareTheHealthBefore() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(200, CRYSTAL_BY_FOO);
        ledger.noteHit(200, CRYSTAL_BY_BAR);
        // Totem: 1 health + 8 absorption. The 20 before is split: 10 each, both ending at 0.
        assertEquals(List.of(lethalHit(200, CRYSTAL_BY_FOO, 10), lethalHit(200, CRYSTAL_BY_BAR, 10)),
            ledger.observe(200, 9, true));
    }

    @Test
    void afterAPopTheBaselineIsTheHealthTheTotemLeft() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(200, CRYSTAL_BY_FOO);
        ledger.observe(200, 9, true);
        assertEquals(List.of(), ledger.observe(201, 9, false));
        ledger.noteHit(205, MELEE_BY_FOO);
        assertEquals(List.of(hit(205, MELEE_BY_FOO, 9, 5)), ledger.observe(205, 5, false));
    }

    @Test
    void theTotemsHealthArrivingLateIsNotAnotherHit() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(300, CRYSTAL_BY_FOO);
        assertEquals(List.of(lethalHit(300, CRYSTAL_BY_FOO, 20)), ledger.observe(300, 20, true));
        // The health update lands after the pop: the drop to 9 is the totem, already counted.
        assertEquals(List.of(), ledger.observe(302, 9, false));
        // Past the window, a drop with no packet is unseen again (the hit at 300 is 4 ticks back).
        assertEquals(List.of(unseen(304, 9, 7)), ledger.observe(304, 7, false));
    }

    @Test
    void aSecondHitRightAfterAPopIsStillCounted() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(300, CRYSTAL_BY_FOO);
        ledger.observe(300, 9, true);
        ledger.noteHit(301, CRYSTAL_BY_BAR);
        assertEquals(List.of(hit(301, CRYSTAL_BY_BAR, 9, 3)), ledger.observe(301, 3, false));
    }

    @Test
    void aPopWithNoPacketIsOneUnseenLethalHit() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        ledger.observe(100, 14, false);
        assertEquals(List.of(new DamageEvent(105, DamageKind.UNSEEN, AttackerKind.NONE, null, 14, 0, true)),
            ledger.observe(105, 9, true));
    }

    @Test
    void onDeathThePendingHitsKillYou() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(400, MELEE_BY_FOO);
        ledger.noteHit(401, OWN_CRYSTAL);
        assertEquals(List.of(lethalHit(400, MELEE_BY_FOO, 10), lethalHit(401, OWN_CRYSTAL, 10)), ledger.lethal(401));
    }

    @Test
    void onDeathAHitOlderThanTheWindowIsNotTheKiller() {
        DamageLedger ledger = ledger(12);
        ledger.noteHit(400, MELEE_BY_FOO);
        assertEquals(List.of(new DamageEvent(404, DamageKind.UNSEEN, AttackerKind.NONE, null, 12, 0, true)),
            ledger.lethal(404));
    }

    @Test
    void onDeathTheBaselineIsTheLastHealthSeen() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(10, CRYSTAL_BY_FOO);
        ledger.observe(10, 6, false);
        ledger.noteHit(20, CRYSTAL_BY_BAR);
        assertEquals(List.of(lethalHit(20, CRYSTAL_BY_BAR, 6)), ledger.lethal(20));
    }

    @Test
    void startForgetsEverything() {
        DamageLedger ledger = ledger(20);
        ledger.noteHit(100, CRYSTAL_BY_FOO);
        ledger.observe(100, 14, false);
        ledger.noteHit(101, MELEE_BY_FOO);
        ledger.start(20);
        // No pending hit, no matched hit: a drop is ignored.
        assertEquals(List.of(), ledger.observe(102, 10, false));
    }

    @Test
    void withoutStartTheFirstHealthSeenIsTheBaseline() {
        DamageLedger ledger = new DamageLedger();
        ledger.noteHit(1, MELEE_BY_FOO);
        assertEquals(List.of(), ledger.observe(1, 18, false));
        assertEquals(List.of(hit(1, MELEE_BY_FOO, 18, 15)), ledger.observe(2, 15, false));
    }
}
