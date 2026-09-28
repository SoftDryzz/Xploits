package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HealthTrust} alone (task B0a, spec Amendment 2026-09-28): whether a judged hit confirms trust, what
 * leaves a hit unjudged (trust unchanged), and the defensive reading of a hostile server's health. Every
 * boundary here is the real one.
 */
class HealthTrustTest {
    private static final String T = "t";
    private static final double D = 5.0;

    @Test
    void theConstantsAreTheBrief() {
        assertEquals(1.0, HealthTrust.TRUST_MIN_DAMAGE, 0.0);
        assertEquals(0.5, HealthTrust.TRUST_DROP_SHARE, 0.0);
    }

    @Test
    void startsUntrusted() {
        assertFalse(new HealthTrust().trusted(T));
    }

    @Test
    void aRealDropAtLeastHalfOfDConfirmsTrust() {
        // before 20, d 5: the drop must be at least 0.5 * min(5, 20) = 2.5, so 17.5 or less.
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, 17.5, false);
        assertTrue(h.trusted(T));
    }

    @Test
    void justUnderTheRequiredDropStaysUntrusted() {
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, Math.nextUp(17.5), false);
        assertFalse(h.trusted(T));
    }

    @Test
    void aFixedValueNeverDroppingIsNeverTrusted() {
        // HealthHider: the server always reports the same number.
        HealthTrust h = new HealthTrust();
        for (int i = 0; i < 5; i++) h.judge(T, D, 20, 20, false);
        assertFalse(h.trusted(T));
    }

    @Test
    void hiddenAbsorptionMakesTheDropTooSmallSoItStaysUntrusted() {
        // AntiHealthIndicator can hide absorption: the real drop (2.5) is masked down to 0.25.
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, 19.75, false);
        assertFalse(h.trusted(T));
    }

    @Test
    void dBelowOneIsNotJudgedTrustUnchanged() {
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, 10, false);
        assertTrue(h.trusted(T), "established first");
        // A tiny-damage hit afterwards (not our crystal, or unmeasured, or d under 1) leaves it as is.
        h.judge(T, Math.nextDown(1.0), 10, 10, false);
        assertTrue(h.trusted(T));
        h.judge(T, Double.NaN, 10, 5, false);
        assertTrue(h.trusted(T));
        h.judge(T, -1, 10, 5, false);
        assertTrue(h.trusted(T));
    }

    @Test
    void aPopInTheSpanMakesTheHitUnjudgedTrustUnchanged() {
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, 17.5, false);
        assertTrue(h.trusted(T), "established first");
        // A real-looking drop, but a pop happened in the same span: not judged either way.
        h.judge(T, D, 20, 1, true);
        assertTrue(h.trusted(T), "a pop leaves trust exactly as it was");

        HealthTrust untrusted = new HealthTrust();
        untrusted.judge(T, D, 20, 20, false);
        assertFalse(untrusted.trusted(T), "established first (fixed, so untrusted)");
        untrusted.judge(T, D, 20, 1, true);
        assertFalse(untrusted.trusted(T), "still unchanged by the popped hit");
    }

    @Test
    void nonFiniteOrNegativeHealthIsUntrustedNeverAThrow() {
        for (double odd : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            HealthTrust before = new HealthTrust();
            before.judge(T, D, 20, 17.5, false);
            assertTrue(before.trusted(T), "established first, odd " + odd);
            before.judge(T, D, odd, 17.5, false);
            assertFalse(before.trusted(T), "odd before " + odd + " forces untrusted, never unchanged");

            HealthTrust now = new HealthTrust();
            now.judge(T, D, 20, 17.5, false);
            assertTrue(now.trusted(T), "established first, odd " + odd);
            now.judge(T, D, 20, odd, false);
            assertFalse(now.trusted(T), "odd now " + odd + " forces untrusted, never unchanged");
        }
    }

    @Test
    void theLatestJudgedHitDecides() {
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, 17.5, false); // trusted
        h.judge(T, D, 20, 20, false); // fixed: untrusted
        assertFalse(h.trusted(T));
        h.judge(T, D, 20, 17.5, false); // trusted again
        assertTrue(h.trusted(T));
    }

    @Test
    void oneTargetsJudgementNeverAffectsAnother() {
        HealthTrust h = new HealthTrust();
        h.judge(T, D, 20, 17.5, false);
        assertTrue(h.trusted(T));
        assertFalse(h.trusted("other"));
    }

    @Test
    void itRejectsWhatCannotBe() {
        assertThrows(NullPointerException.class, () -> new HealthTrust().trusted(null));
        assertThrows(NullPointerException.class, () -> new HealthTrust().judge(null, D, 20, 17.5, false));
    }
}
