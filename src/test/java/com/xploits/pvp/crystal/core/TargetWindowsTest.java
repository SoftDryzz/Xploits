package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tasks R3-3 and R3-11: {@link TargetWindows} alone (the landing condition learned from our own crystals' landings,
 * the margin, the last hit per target) and the values it is fed with: the exact raw per target on {@link Candidate} and {@link CrystalSeen}, the ping on
 * {@link CrystalTick}, and {@link ServerValues#targetRaw}. Values exact in binary, so each boundary is the
 * real one.
 */
class TargetWindowsTest {
    private static final String T = "t";

    /** A window with five landings of 2 measured at tick 0: a crystal placed now lands inside it up to 6 ticks on. */
    private static TargetWindows trained() {
        TargetWindows w = new TargetWindows();
        for (int i = 0; i < TargetWindows.LANDING_MIN_SAMPLES; i++) w.landed(0, 2);
        return w;
    }

    private static TargetWindows landings(long at, int... ticks) {
        TargetWindows w = new TargetWindows();
        for (int t : ticks) w.landed(at, t);
        return w;
    }

    @Test
    void theConstantsAreTheBriefs() {
        assertEquals(10, TargetWindows.HURT_WINDOW_TICKS);
        assertEquals(0.5, TargetWindows.RAW_MARGIN);
        assertEquals(20, TargetWindows.LANDING_SAMPLES);
        assertEquals(5, TargetWindows.LANDING_MIN_SAMPLES);
        assertEquals(1, TargetWindows.LANDING_MARGIN);
        assertEquals(200, TargetWindows.LANDING_SAMPLE_MAX_AGE);
        assertEquals(20, TargetWindows.LANDING_SAMPLE_MAX_TICKS);
    }

    @Test
    void theLandingIsSurelyInsideOnlyBelowTheWindowWithTheSlowestLandingAndTheMargin() {
        // Five landings of 3: 5 + 3 + 1 = 9 < 10, 6 + 3 + 1 = 10.
        TargetWindows w = landings(0, 3, 3, 3, 3, 3);
        assertTrue(w.landsInside(5, 0));
        assertFalse(w.landsInside(6, 0));
    }

    @Test
    void withFewerThanFiveLandingsNothingLandsSurelyInside() {
        assertFalse(new TargetWindows().landsInside(0, 0));
        assertFalse(landings(0, 3, 3, 3, 3).landsInside(0, 0));
        assertTrue(landings(0, 3, 3, 3, 3, 3).landsInside(0, 0));
    }

    @Test
    void theSlowestLandingSetsTheBound() {
        // 2, 2, 2, 2, 6: 2 + 6 + 1 = 9 < 10, 3 + 6 + 1 = 10.
        TargetWindows w = landings(0, 2, 2, 2, 2, 6);
        assertTrue(w.landsInside(2, 0));
        assertFalse(w.landsInside(3, 0));
    }

    @Test
    void onlyTheLastTwentyLandingsCount() {
        TargetWindows w = landings(0, 9);
        for (int i = 0; i < 19; i++) w.landed(0, 2);
        // Twenty landings, the 9 among them: 0 + 9 + 1 = 10.
        assertFalse(w.landsInside(0, 0));
        // The twenty-first pushes the 9 out: 6 + 2 + 1 = 9 < 10.
        w.landed(0, 2);
        assertTrue(w.landsInside(6, 0));
    }

    @Test
    void aLandingOlderThanTwoHundredTicksNoLongerCounts() {
        TargetWindows w = new TargetWindows();
        for (long at = 100; at < 105; at++) w.landed(at, 3);
        // At 300 the first is 200 ticks old and still counts; at 301 it is 201 and four remain.
        assertTrue(w.landsInside(5, 300));
        assertFalse(w.landsInside(5, 301));
        w.expire(300);
        assertTrue(w.landsInside(5, 300));
        w.expire(301);
        assertFalse(w.landsInside(5, 300));
    }

    @Test
    void aLandingSlowerThanTwentyTicksIsIgnoredNotClamped() {
        // 20 still counts (0 + 20 + 1 is not under 10); 21 and 25 are left out, so the bound stays 3.
        TargetWindows kept = landings(0, 3, 3, 3, 3, 3, 20);
        assertFalse(kept.landsInside(0, 0));
        for (int outlier : new int[] {21, 25}) {
            TargetWindows w = landings(0, 3, 3, 3, 3, 3, outlier);
            assertTrue(w.landsInside(5, 0), "outlier " + outlier);
            assertFalse(landings(0, 3, 3, 3, 3, outlier).landsInside(0, 0), "outlier " + outlier + " as the fifth");
        }
    }

    @Test
    void clearingForgetsTheLandings() {
        TargetWindows w = trained();
        w.clear();
        assertFalse(w.landsInside(0, 0));
    }

    // recentOutlier() (task R3-16 fix round 1): a too-slow landing must not simply vanish

    @Test
    void withNoLandingAtAllThereIsNoRecentOutlier() {
        assertFalse(new TargetWindows().recentOutlier(0));
    }

    @Test
    void anOrdinaryLandingIsNeverAnOutlier() {
        TargetWindows w = landings(0, 3, 3, 3, 3, 3, 20);
        assertFalse(w.recentOutlier(0), "20 is still an ordinary sample, not an outlier");
    }

    @Test
    void aLandingPastTheCeilingMarksARecentOutlierWithoutBecomingASample() {
        TargetWindows w = landings(0, 3, 3, 3, 3, 3, 25);
        assertTrue(w.recentOutlier(0));
        // Unchanged from before this fix: the outlier still never joins the ordinary samples.
        assertTrue(w.landsInside(5, 0), "the bound is still 3, from the five ordinary samples");
    }

    @Test
    void aRecentOutlierAgesOutAfterTheSameWindowAsAnOrdinarySample() {
        TargetWindows w = new TargetWindows();
        w.landed(100, 25);
        assertTrue(w.recentOutlier(300), "200 ticks old and still counts");
        assertFalse(w.recentOutlier(301), "201 ticks old, one past the window");
    }

    @Test
    void expiringAlsoForgetsAnAgedOutOutlier() {
        TargetWindows w = new TargetWindows();
        w.landed(100, 25);
        w.expire(300);
        assertTrue(w.recentOutlier(300));
        w.expire(301);
        assertFalse(w.recentOutlier(301));
    }

    @Test
    void clearingForgetsARecentOutlierToo() {
        TargetWindows w = new TargetWindows();
        w.landed(0, 25);
        w.clear();
        assertFalse(w.recentOutlier(0));
    }

    @Test
    void aHitFromOurCrystalSwallowsWhatDoesNotBeatItByTheMargin() {
        TargetWindows w = trained();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        assertTrue(w.swallows(T, 47.0, 12));
        assertTrue(w.swallows(T, 1.0, 12));
        assertFalse(w.swallows(T, 47.25, 12));
        assertFalse(w.swallows("someone else", 1.0, 12));
        // 6 + 2 + 1 = 9 < 10; 7 + 2 + 1 = 10.
        assertTrue(w.swallows(T, 47.0, 16));
        assertFalse(w.swallows(T, 47.0, 17));
    }

    @Test
    void withNoLandingMeasuredNothingIsSwallowed() {
        TargetWindows w = new TargetWindows();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        assertFalse(w.swallows(T, 1.0, 11));
    }

    @Test
    void onlyTheLastHitCounts() {
        TargetWindows w = trained();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        w.fullHit(T, 11, OptionalDouble.empty());
        assertFalse(w.swallows(T, 1.0, 12));
        w.fullHit(T, 12, OptionalDouble.of(20));
        assertTrue(w.swallows(T, 19.5, 13));
        assertFalse(w.swallows(T, 19.75, 13));
    }

    @Test
    void anExpiredHitIsForgotten() {
        TargetWindows w = new TargetWindows();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        w.expire(19);
        assertTrue(w.remembers(T));
        w.expire(20);
        assertFalse(w.remembers(T));
    }

    @Test
    void clearingForgetsEveryHit() {
        TargetWindows w = trained();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        w.fullHit("u", 10, OptionalDouble.of(47.5));
        w.clear();
        assertFalse(w.remembers(T));
        assertFalse(w.remembers("u"));
        assertFalse(w.swallows(T, 1.0, 11));
    }

    @Test
    void oddValuesAreRefused() {
        TargetWindows w = new TargetWindows();
        assertThrows(IllegalArgumentException.class, () -> w.fullHit(T, 1, OptionalDouble.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> w.fullHit(T, 1, OptionalDouble.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> w.swallows(T, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> w.landed(1, -1));
        assertThrows(NullPointerException.class, () -> w.fullHit(null, 1, OptionalDouble.empty()));
    }

    // What it is fed with

    @Test
    void recordsCarryTheExactRawAndTheShortConstructorsNone() {
        Candidate c = new Candidate(1, Map.of(T, 5.0), Map.of(T, 47.5), 1, 1.5, true, Set.of(), false);
        assertEquals(Map.of(T, 47.5), c.targetRaw());
        assertEquals(Map.of(), new Candidate(1, Map.of(T, 5.0), 1, true, Set.of(), false).targetRaw());
        assertEquals(Map.of(), new Candidate(1, Map.of(T, 5.0), 1, 1.5, true, Set.of(), false).targetRaw());

        CrystalSeen s = new CrystalSeen(1, 1, Map.of(T, 5.0), Map.of(T, 47.5), 1, 1.5, 3, true);
        assertEquals(Map.of(T, 47.5), s.targetRaw());
        assertEquals(Map.of(), new CrystalSeen(1, 1, Map.of(T, 5.0), 1, 3, true).targetRaw());
        assertEquals(Map.of(), new CrystalSeen(1, 1, Map.of(T, 5.0), 1, 1.5, 3, true).targetRaw());

        for (double odd : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class,
                () -> new Candidate(1, Map.of(), Map.of(T, odd), 1, 1, true, Set.of(), false), "raw " + odd);
            assertThrows(IllegalArgumentException.class,
                () -> new CrystalSeen(1, 1, Map.of(), Map.of(T, odd), 1, 1, 3, true), "raw " + odd);
        }
    }

    @Test
    void theTickCarriesThePingAndAnUnknownOneByDefault() {
        assertEquals(3, new CrystalTick(1, 20, 0, false, false, false, false, HANDS, List.of(), List.of(), List.of(), 3)
            .pingTicks());
        assertEquals(CrystalBrain.UNKNOWN_PING_TICKS,
            new CrystalTick(1, 20, 0, false, false, false, false, HANDS, List.of(), List.of(), List.of()).pingTicks());
        assertThrows(IllegalArgumentException.class,
            () -> new CrystalTick(1, 20, 0, false, false, false, false, HANDS, List.of(), List.of(), List.of(), -1));
    }

    @Test
    void anOddRawToATargetIsLeftOutSoItNeverDefers() {
        assertEquals(OptionalDouble.of(47.5), ServerValues.targetRaw(47.5));
        assertEquals(OptionalDouble.of(0), ServerValues.targetRaw(0));
        for (double odd : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertTrue(ServerValues.targetRaw(odd).isEmpty(), "raw " + odd);
        }
    }
}
