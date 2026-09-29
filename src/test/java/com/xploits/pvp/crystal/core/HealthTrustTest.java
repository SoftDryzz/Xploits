package com.xploits.pvp.crystal.core;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HealthTrust} alone (task B0a, spec Amendment 2026-09-28; the judging window is task T1): whether a
 * judged hit confirms trust, what leaves a hit unjudged (trust unchanged), the health arriving some pre-ticks
 * after the damage packet, and the defensive reading of a hostile server's health.
 */
class HealthTrustTest {
    private static final String T = "t";
    private static final double D = 5.0;
    private static final Set<String> NO_POPS = Set.of();

    /** Reads one pre-tick of the target's reported health. */
    private static void read(HealthTrust h, double health) {
        h.advance(Map.of(T, health), NO_POPS);
    }

    /** A hit counted at a pre-tick that reads {@code first}, then more pre-ticks reading {@code later}. */
    private static HealthTrust hitThen(double before, double first, double... later) {
        HealthTrust h = new HealthTrust();
        h.hit(T, D, before, false);
        read(h, first);
        for (double v : later) read(h, v);
        return h;
    }

    @Test
    void theConstantsAreTheBrief() {
        assertEquals(1.0, HealthTrust.TRUST_MIN_DAMAGE, 0.0);
        assertEquals(0.5, HealthTrust.TRUST_DROP_SHARE, 0.0);
        assertEquals(3, HealthTrust.TRUST_JUDGE_TICKS);
    }

    @Test
    void startsUntrusted() {
        assertFalse(new HealthTrust().trusted(T));
    }

    @Test
    void aRealDropAtLeastHalfOfDConfirmsTrust() {
        // before 20, d 5: the drop must be at least 0.5 * min(5, 20) = 2.5, so 17.5 or less.
        assertTrue(hitThen(20, 17.5).trusted(T));
    }

    @Test
    void justUnderTheRequiredDropNeverConfirmsTrust() {
        double under = Math.nextUp(17.5);
        assertFalse(hitThen(20, under, under, under, under).trusted(T));
    }

    @Test
    void theHealthArrivingOneTwoOrThreePreTicksLateIsStillTrusted() {
        assertTrue(hitThen(20, 20, 17.5).trusted(T), "1 late");
        assertTrue(hitThen(20, 20, 20, 17.5).trusted(T), "2 late");
        assertTrue(hitThen(20, 20, 20, 20, 17.5).trusted(T), "3 late");
    }

    @Test
    void theHealthArrivingFourPreTicksLateIsUntrusted() {
        HealthTrust h = new HealthTrust();
        h.hit(T, D, 20, false);
        for (int i = 0; i < 4; i++) read(h, 20);
        assertFalse(h.trusted(T));
        read(h, 17.5); // nobody is looking any more
        assertFalse(h.trusted(T));
    }

    @Test
    void whileAHitIsPendingTrustStaysAsItWas() {
        HealthTrust trusted = hitThen(20, 17.5);
        trusted.hit(T, D, 17.5, false);
        read(trusted, 17.5); // the race: the drop has not arrived yet
        read(trusted, 17.5);
        read(trusted, 17.5);
        assertTrue(trusted.trusted(T), "still trusted on the last pre-tick of the window");
        read(trusted, 15);
        assertTrue(trusted.trusted(T));
    }

    @Test
    void anAlreadyTrustedTargetSeeingTheRaceStaysTrusted() {
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, 17.5, false);
        read(h, 17.5); // late
        assertTrue(h.trusted(T));
        read(h, 12.5); // arrives
        assertTrue(h.trusted(T));
    }

    @Test
    void anAlreadyTrustedTargetWhoseWindowEndsWithNoDropBecomesUntrusted() {
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, 17.5, false);
        for (int i = 0; i < 4; i++) read(h, 17.5);
        assertFalse(h.trusted(T));
    }

    @Test
    void aFixedValueNeverDroppingIsNeverTrusted() {
        // HealthHider: the server always reports the same number, however many hits.
        HealthTrust h = new HealthTrust();
        for (int hit = 0; hit < 5; hit++) {
            h.hit(T, D, 1, false);
            for (int i = 0; i < 5; i++) read(h, 1);
            assertFalse(h.trusted(T));
        }
    }

    @Test
    void hiddenAbsorptionMakesTheDropTooSmallSoItStaysUntrusted() {
        // AntiHealthIndicator can hide absorption: the real drop (2.5) is masked down to 0.25, at every pre-tick.
        assertFalse(hitThen(20, 19.75, 19.75, 19.75, 19.75, 19.75).trusted(T));
    }

    @Test
    void dBelowOneIsNotJudgedTrustUnchanged() {
        HealthTrust h = hitThen(20, 10);
        assertTrue(h.trusted(T), "established first");
        // A tiny-damage hit afterwards (not our crystal, or unmeasured, or d under 1) leaves it as is.
        for (double d : new double[] {Math.nextDown(1.0), Double.NaN, -1}) {
            h.hit(T, d, 10, false);
            for (int i = 0; i < 5; i++) read(h, 10);
            assertTrue(h.trusted(T), "d " + d);
        }
    }

    @Test
    void aPopInsideTheWindowLeavesTheHitUnjudgedTrustUnchanged() {
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, 17.5, false);
        read(h, 17.5);
        h.advance(Map.of(T, 1.0), Set.of(T)); // the pop, and health 1
        for (int i = 0; i < 5; i++) read(h, 1);
        assertTrue(h.trusted(T), "a pop leaves trust exactly as it was");

        HealthTrust untrusted = new HealthTrust();
        untrusted.hit(T, D, 20, false);
        read(untrusted, 20);
        untrusted.advance(Map.of(T, 1.0), Set.of(T));
        for (int i = 0; i < 5; i++) read(untrusted, 1);
        assertFalse(untrusted.trusted(T), "a popped hit is never trusted from the health it leaves");
    }

    @Test
    void aPopInTheSpanBeforeTheHitMakesItUnjudged() {
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, 17.5, true);
        for (int i = 0; i < 5; i++) read(h, 17.5);
        assertTrue(h.trusted(T));

        HealthTrust untrusted = new HealthTrust();
        untrusted.hit(T, D, 20, true);
        read(untrusted, 1);
        assertFalse(untrusted.trusted(T));
    }

    @Test
    void nonFiniteOrNegativeHealthIsUntrustedNeverAThrow() {
        for (double odd : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            HealthTrust before = hitThen(20, 17.5);
            assertTrue(before.trusted(T), "established first, odd " + odd);
            before.hit(T, D, odd, false);
            assertFalse(before.trusted(T), "odd before " + odd + " forces untrusted, never unchanged");

            HealthTrust now = hitThen(20, 17.5);
            now.hit(T, D, 20, false);
            read(now, odd);
            assertFalse(now.trusted(T), "odd now " + odd + " forces untrusted, never unchanged");
        }
    }

    @Test
    void aTargetMissingFromThePreTickReadsAsBadHealth() {
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, 17.5, false);
        h.advance(Map.of(), NO_POPS);
        assertFalse(h.trusted(T));
    }

    @Test
    void nonFiniteBeforeForcesUntrustedEvenWithAPopInTheSameSpan() {
        // Task B0a review round 1 (minor): bad health wins the overlap with a pop, forcing untrusted.
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, Double.NaN, true);
        assertFalse(h.trusted(T));
    }

    @Test
    void aNewerHitReplacesAPendingOlderOne() {
        // Overlapping windows: the older hit is still waiting when a newer one is read. Only the newer window is
        // judged, from its own before and its own 4 reads; the older is dropped without a verdict.
        HealthTrust h = new HealthTrust();
        h.hit(T, D, 20, false);
        read(h, 20);
        h.hit(T, D, 20, false);
        read(h, 20);
        read(h, 20);
        read(h, 20); // the older window would have ended here (4 reads); the newer has had 3
        assertFalse(h.trusted(T), "no verdict yet: the newer window is still open");
        read(h, 17.5); // the newer window's 4th read shows the drop
        assertTrue(h.trusted(T));
    }

    @Test
    void theLatestJudgedHitDecides() {
        HealthTrust h = hitThen(20, 17.5); // trusted
        h.hit(T, D, 17.5, false);
        for (int i = 0; i < 4; i++) read(h, 17.5); // fixed: untrusted
        assertFalse(h.trusted(T));
        h.hit(T, D, 17.5, false);
        read(h, 15);
        assertTrue(h.trusted(T));
    }

    @Test
    void anUnjudgeableNewerHitLeavesTheOlderWindowRunning() {
        // Trusted, our hit opens a window, a foreign hit (d < 1) is read, then the health never moves: the older
        // window must still end untrusted (a foreign hit cannot shield trust from the verdict).
        HealthTrust h = hitThen(20, 17.5);
        assertTrue(h.trusted(T));
        h.hit(T, D, 17.5, false);
        read(h, 17.5);
        h.hit(T, Double.NaN, 17.5, false);
        h.hit(T, Math.nextDown(1.0), 17.5, false);
        read(h, 17.5);
        read(h, 17.5);
        assertTrue(h.trusted(T), "still pending");
        read(h, 17.5);
        assertFalse(h.trusted(T), "the older window ended with no change");
    }

    @Test
    void anUnjudgeableNewerHitDoesNotReplaceTheOlderWindowsBefore() {
        // The older window keeps its own before: a later qualifying drop still trusts it.
        HealthTrust h = new HealthTrust();
        h.hit(T, D, 20, false);
        read(h, 20);
        h.hit(T, 0.5, 20, false);
        read(h, 17.5);
        assertTrue(h.trusted(T));
    }

    @Test
    void theFirstChangeDecidesEvenIfALaterReadingWouldQualify() {
        // A first change that does not qualify (a random-looking value) untrusts at once; a later qualifying
        // value is nobody's verdict any more.
        HealthTrust h = new HealthTrust();
        h.hit(T, D, 20, false);
        read(h, 19);
        assertFalse(h.trusted(T));
        read(h, 15);
        assertFalse(h.trusted(T));

        HealthTrust trusted = hitThen(20, 17.5);
        trusted.hit(T, D, 17.5, false);
        read(trusted, 17.5);
        read(trusted, 18.5); // a rise is a change too, and not a qualifying drop
        assertFalse(trusted.trusted(T), "a trusted target whose first change does not qualify is untrusted");
        read(trusted, 12);
        assertFalse(trusted.trusted(T));
    }

    @Test
    void aRandomisingServerGetsOneDrawPerHitNotOnePerReading() {
        // Readings 19, 15, 14, 13: only the first counts; the later ones would each have qualified.
        assertFalse(hitThen(20, 20, 19, 15, 14).trusted(T));
        // The same first change qualifying trusts, whatever follows.
        assertTrue(hitThen(20, 20, 15, 20, 20).trusted(T));
    }

    @Test
    void aBadBeforeDropsAPendingHitSoItCannotTrustLater() {
        HealthTrust h = new HealthTrust();
        h.hit(T, D, 20, false);
        h.hit(T, D, Double.NaN, false);
        read(h, 15);
        assertFalse(h.trusted(T), "the stale pending hit must be gone");
    }

    @Test
    void twoTargetsAreJudgedIndependently() {
        HealthTrust h = new HealthTrust();
        h.hit("a", D, 20, false);
        h.hit("b", D, 20, false);
        h.advance(Map.of("a", 15.0, "b", 20.0), NO_POPS);
        assertTrue(h.trusted("a"));
        assertFalse(h.trusted("b"));
        h.advance(Map.of("a", 15.0, "b", 15.0), NO_POPS);
        assertTrue(h.trusted("b"), "b's window is still open on its own");
        h.hit("a", D, 15, false);
        h.advance(Map.of("a", 15.0, "b", 15.0), Set.of("a"));
        assertTrue(h.trusted("a"), "a pop of a leaves b alone and a unchanged");
        assertTrue(h.trusted("b"));
    }

    @Test
    void aPoppedNewerHitRemovesThePendingOlderOne() {
        HealthTrust h = new HealthTrust();
        h.hit(T, D, 20, false);
        read(h, 20);
        h.hit(T, D, 20, true); // a pop was read in this hit's span
        read(h, 15); // would have trusted the older hit
        assertFalse(h.trusted(T));
    }

    @Test
    void forgettingPendingHitsLeavesTrustAsItWas() {
        HealthTrust h = hitThen(20, 17.5);
        h.hit(T, D, 17.5, false);
        h.forgetPending();
        for (int i = 0; i < 5; i++) read(h, 17.5);
        assertTrue(h.trusted(T));
    }

    @Test
    void oneTargetsJudgementNeverAffectsAnother() {
        HealthTrust h = hitThen(20, 17.5);
        assertTrue(h.trusted(T));
        assertFalse(h.trusted("other"));
    }

    @Test
    void itRejectsWhatCannotBe() {
        assertThrows(NullPointerException.class, () -> new HealthTrust().trusted(null));
        assertThrows(NullPointerException.class, () -> new HealthTrust().hit(null, D, 20, false));
        assertThrows(NullPointerException.class, () -> new HealthTrust().advance(null, NO_POPS));
    }
}
