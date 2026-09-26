package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Our own hurt cooldown, credited conservatively (spec, Round 2 (a), conditions 1-5): each condition on and
 * just off its edge. Condition 1 (the packet is ours: player_explosion, cause us, direct one of our crystals)
 * is decided before a hit reaches {@link HurtWindow#ownHit}; see {@code CrystalBrainCooldownTest}. Every value
 * here is exact in binary.
 */
class HurtWindowTest {
    private static final long HIT = 100;
    private static final double LAST = 12.0;

    /** A window opened at {@link #HIT} by a hit of raw {@link #LAST}, with health 20 seen just before it. */
    private static HurtWindow opened(int rtt) {
        HurtWindow w = new HurtWindow();
        w.health(HIT, 20);
        w.ownHit(HIT, LAST, rtt);
        return w;
    }

    @Test
    void theNumbersAreTheSpecs() {
        assertEquals(20, HurtWindow.REGEN_TICKS);
        assertEquals(10, HurtWindow.COOLDOWN_ABOVE);
        assertEquals(2, HurtWindow.MARGIN_TICKS);
        assertEquals(0.5, HurtWindow.RAW_MARGIN, 0.0);
        assertEquals(5, HurtWindow.UNKNOWN_RTT_TICKS);
        assertEquals(CrystalBrain.UNKNOWN_PING_TICKS, HurtWindow.UNKNOWN_RTT_TICKS);
        assertEquals(1.5, HurtWindow.HARDEST_SCALING, 0.0);
    }

    @Test
    void nothingIsCreditedBeforeAnyHit() {
        HurtWindow w = new HurtWindow();
        w.health(HIT, 20);
        assertFalse(w.credits(HIT + 1, false, 1));
        assertFalse(HurtWindow.credits(HurtWindow.NONE, LAST, 0, false, HIT, false, 1));
    }

    // Condition 2: 20 - k > 10 + RTT + 2, with the full round trip

    @Test
    void withNoLatencyTheCreditLastsUntilSevenTicksAfterThePacket() {
        HurtWindow w = opened(0);
        // 20 - 7 = 13 > 12
        assertTrue(w.credits(HIT + 7, false, 10));
        // 20 - 8 = 12, not > 12
        assertFalse(w.credits(HIT + 8, false, 10));
    }

    @Test
    void theFullRoundTripIsCountedNotHalfOfIt() {
        HurtWindow w = opened(4);
        // 20 - 3 = 17 > 10 + 4 + 2 = 16
        assertTrue(w.credits(HIT + 3, false, 10));
        // 20 - 4 = 16: with half the round trip (2) it would still pass
        assertFalse(w.credits(HIT + 4, false, 10));
    }

    @Test
    void anUnknownLatencyCountsFiveTicks() {
        HurtWindow w = opened(HurtWindow.UNKNOWN_RTT_TICKS);
        // 20 - 2 = 18 > 17
        assertTrue(w.credits(HIT + 2, false, 10));
        assertFalse(w.credits(HIT + 3, false, 10));
    }

    @Test
    void aLatencyAtOrAboveEightTicksNeverCredits() {
        HurtWindow w = opened(7);
        assertTrue(w.credits(HIT, false, 10));
        assertFalse(opened(8).credits(HIT, false, 10));
    }

    @Test
    void aCreditIsNeverAskedForBeforeTheHit() {
        HurtWindow w = opened(0);
        assertThrows(IllegalArgumentException.class, () -> w.credits(HIT - 1, false, 10));
        assertThrows(IllegalArgumentException.class, () -> w.ownHit(HIT + 1, LAST, -1));
    }

    // Condition 3: no shield

    @Test
    void blockingWithAShieldNeverCredits() {
        HurtWindow w = opened(0);
        assertTrue(w.credits(HIT + 1, false, 10));
        assertFalse(w.credits(HIT + 1, true, 10));
    }

    // Condition 4: R_new + 0.5 <= R_last - 0.5, unrounded

    @Test
    void theNewRawMustBeAWholePointBelowTheLast() {
        HurtWindow w = opened(0);
        // 11 + 0.5 = 12 - 0.5
        assertTrue(w.credits(HIT + 1, false, 11));
        assertFalse(w.credits(HIT + 1, false, 11.0625));
        assertFalse(w.credits(HIT + 1, false, LAST));
        assertFalse(w.credits(HIT + 1, false, 20));
    }

    @Test
    void theRawIsComparedUnroundedNotTruncatedAsMeteorWould() {
        // Truncated (Meteor's int): 10 + 0.5 <= 11 - 0.5 would credit it. Unrounded: 11.375 > 11.25.
        HurtWindow w = new HurtWindow();
        w.health(HIT, 20);
        w.ownHit(HIT, 11.75, 0);
        assertFalse(w.credits(HIT + 1, false, 10.875));
        assertTrue(w.credits(HIT + 1, false, 10.75));
    }

    @Test
    void aBiggerNewHitIsNeverCreditedEvenWhenBothTruncateAlike() {
        // 10.9 and 10.1 both truncate to 10: equal for Meteor. The new one is bigger: it would do the difference.
        HurtWindow w = new HurtWindow();
        w.health(HIT, 20);
        w.ownHit(HIT, 10.1, 0);
        assertFalse(w.credits(HIT + 1, false, 10.9));
        assertFalse(HurtWindow.credits(HIT, 10.1, 0, false, HIT + 1, false, 10.9));
    }

    @Test
    void anUnknownRawNeverCredits() {
        HurtWindow w = opened(0);
        assertFalse(w.credits(HIT + 1, false, RawExplosion.UNKNOWN));
        HurtWindow unknownLast = new HurtWindow();
        unknownLast.health(HIT, 20);
        unknownLast.ownHit(HIT, RawExplosion.UNKNOWN, 0);
        assertFalse(unknownLast.credits(HIT + 1, false, 1));
        assertFalse(HurtWindow.credits(HIT, Double.NaN, 0, false, HIT + 1, false, 1));
        assertFalse(HurtWindow.credits(HIT, LAST, 0, false, HIT + 1, false, Double.NaN));
    }

    // Condition 5: nothing else hit us since

    @Test
    void anotherHitCancelsTheCreditUntilOurNextOwnHit() {
        HurtWindow w = opened(0);
        w.otherHit();
        assertFalse(w.credits(HIT + 1, false, 10));
        assertFalse(HurtWindow.credits(HIT, LAST, 0, true, HIT + 1, false, 10));

        // A new full hit by our own crystal opens a new window.
        w.health(HIT + 2, 20);
        w.ownHit(HIT + 2, LAST, 0);
        assertTrue(w.credits(HIT + 3, false, 10));
    }

    @Test
    void theHitsOwnDropExplainedRightAfterThePacketKeepsTheCredit() {
        HurtWindow w = opened(0);
        // Up to 1.5 * (12 + 0.5) = 18.75, seen within two pre-ticks of the packet.
        w.health(HIT + 1, 12);
        w.health(HIT + 2, 1.25);
        assertTrue(w.credits(HIT + 3, false, 10));
    }

    @Test
    void aDropBeyondWhatTheHitCanDealCancels() {
        HurtWindow w = opened(0);
        w.health(HIT + 1, 1.0);
        assertFalse(w.credits(HIT + 2, false, 10));
    }

    @Test
    void aDropSeenLaterThanTheHitCanShowCancels() {
        HurtWindow w = opened(0);
        w.health(HIT + 1, 14);
        w.health(HIT + 2, 14);
        assertTrue(w.credits(HIT + 3, false, 10));
        w.health(HIT + 3, 13.5);
        assertFalse(w.credits(HIT + 3, false, 10));
    }

    @Test
    void healingInBetweenDoesNotHideASecondDrop() {
        HurtWindow w = opened(0);
        w.health(HIT + 1, 10);
        w.health(HIT + 1, 20);
        // 10 + 9 = 19 > 18.75, although no reading was ever more than 10 below the start.
        w.health(HIT + 2, 11);
        assertFalse(w.credits(HIT + 3, false, 10));
    }

    @Test
    void theWindowCountsFromThePacketNotFromTheHealthDrop() {
        // The client restarts its own timer on any health drop; only the packet says when the server's began.
        HurtWindow w = opened(0);
        w.health(HIT + 2, 14);
        assertTrue(w.credits(HIT + 7, false, 10));
        assertFalse(w.credits(HIT + 8, false, 10));
        assertFalse(w.credits(HIT + 9, false, 10));
    }

    @Test
    void aHitWithNoHealthSeenBeforeItNeverCredits() {
        // With no health to start from, a drop could not be told apart: nothing is credited.
        HurtWindow w = new HurtWindow();
        w.ownHit(HIT, LAST, 0);
        w.health(HIT + 1, 14);
        assertFalse(w.credits(HIT + 1, false, 10));
    }
}
