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
 * Task R3-3: {@link TargetWindows} alone (the landing condition, the margin, the last hit per target) and the
 * values it is fed with: the exact raw per target on {@link Candidate} and {@link CrystalSeen}, the ping on
 * {@link CrystalTick}, and {@link ServerValues#targetRaw}. Values exact in binary, so each boundary is the
 * real one.
 */
class TargetWindowsTest {
    private static final String T = "t";

    @Test
    void theLandingIsSurelyInsideOnlyBelowTheWindow() {
        assertEquals(10, TargetWindows.HURT_WINDOW_TICKS);
        assertEquals(2, TargetWindows.LANDING_SLACK_TICKS);
        assertEquals(0.5, TargetWindows.RAW_MARGIN);
        assertTrue(TargetWindows.landsInside(7, 0));
        assertFalse(TargetWindows.landsInside(8, 0));
        assertTrue(TargetWindows.landsInside(1, 3));
        assertFalse(TargetWindows.landsInside(2, 3));
        assertFalse(TargetWindows.landsInside(0, CrystalBrain.UNKNOWN_PING_TICKS));
    }

    @Test
    void aHitFromOurCrystalSwallowsWhatDoesNotBeatItByTheMargin() {
        TargetWindows w = new TargetWindows();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        assertTrue(w.swallows(T, 47.0, 12, 0));
        assertTrue(w.swallows(T, 1.0, 12, 0));
        assertFalse(w.swallows(T, 47.25, 12, 0));
        assertFalse(w.swallows("someone else", 1.0, 12, 0));
        assertFalse(w.swallows(T, 47.0, 18, 0));
    }

    @Test
    void onlyTheLastHitCounts() {
        TargetWindows w = new TargetWindows();
        w.fullHit(T, 10, OptionalDouble.of(47.5));
        w.fullHit(T, 11, OptionalDouble.empty());
        assertFalse(w.swallows(T, 1.0, 12, 0));
        w.fullHit(T, 12, OptionalDouble.of(20));
        assertTrue(w.swallows(T, 19.5, 13, 0));
        assertFalse(w.swallows(T, 19.75, 13, 0));
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
    void oddValuesAreRefused() {
        TargetWindows w = new TargetWindows();
        assertThrows(IllegalArgumentException.class, () -> w.fullHit(T, 1, OptionalDouble.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> w.fullHit(T, 1, OptionalDouble.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> w.swallows(T, Double.NaN, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> w.swallows(T, 1, 1, -1));
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
