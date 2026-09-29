package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.MovementReach.Offset;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task C3: while we move (reach radius above {@link MovementReach#STILL_RADIUS}) the worst case is exactly 0.7.0's;
 * at or below it, it is task B2's (real exposure at the reach points).
 */
class MovementReachMovingTest {
    /** A verbatim copy of 0.7.0's algorithm (MovementReach.offsets + worstRawDamage at 4a71683), kept as the oracle. */
    private static float legacy070(double ex, double ey, double ez, double vx, double vz, long landingTicks) {
        double safeVx = Double.isFinite(vx) ? vx : 0;
        double safeVz = Double.isFinite(vz) ? vz : 0;
        long ticks = Math.max(0, landingTicks);
        double radius = Math.hypot(safeVx, safeVz) * ticks;
        float worst = 0f;
        for (double h : new double[]{0, 1.25}) {
            double[][] pts = new double[2 + (radius > 0 ? 8 : 0)][];
            pts[0] = new double[]{0, 0};
            pts[1] = new double[]{safeVx * ticks, safeVz * ticks};
            if (radius > 0) {
                for (int i = 0; i < 8; i++) {
                    double angle = 2 * Math.PI * i / 8;
                    pts[2 + i] = new double[]{radius * Math.cos(angle), radius * Math.sin(angle)};
                }
            }
            for (double[] p : pts) {
                double dx = ex - p[0], dy = ey - h, dz = ez - p[1];
                worst = Math.max(worst, ExplosionMath.rawDamage(Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0));
            }
        }
        return worst;
    }

    /** Today's (B2 + fix rounds) path, as the adapter called it before C3. */
    private static float b2(double ex, double ey, double ez, double vx, double vz, long ticks,
                            MovementReach.ExposureFunction e, float floor) {
        return MovementReach.worstRawDamage(MovementReach.rankedByWorstRaw(ex, ey, ez,
            MovementReach.reachPoints(vx, vz, ticks)), e, floor);
    }

    private static MovementReach.ExposureFunction covered(double value) {
        return o -> o.dy() > 0 ? 1.0 : value;
    }

    @Test
    void aboveTheStillRadiusTheResultIs070sWorstCaseForRandomInputs() {
        Random rnd = new Random(71);
        int checked = 0;
        for (int i = 0; i < 3000; i++) {
            double vx = (rnd.nextDouble() - 0.5) * 0.8, vz = (rnd.nextDouble() - 0.5) * 0.8;
            long ticks = rnd.nextInt(14);
            if (MovementReach.readsRealExposure(vx, vz, ticks)) continue;
            double ex = (rnd.nextDouble() - 0.5) * 14, ey = (rnd.nextDouble() - 0.5) * 8, ez = (rnd.nextDouble() - 0.5) * 14;
            float floor = rnd.nextFloat() * 10;
            AtomicInteger asked = new AtomicInteger();
            float got = MovementReach.worstReachRaw(ex, ey, ez, vx, vz, ticks, o -> {
                asked.incrementAndGet();
                return rnd.nextDouble();
            }, floor);
            float expected = Math.max(floor, legacy070(ex, ey, ez, vx, vz, ticks));
            assertEquals(expected, got, 0f, "vx=" + vx + " vz=" + vz + " ticks=" + ticks);
            assertEquals(0, asked.get(), "no exposure is asked above the still radius");
            checked++;
        }
        assertTrue(checked > 1000, "the property must exercise the moving branch: " + checked);
    }

    @Test
    void theLegacyOracleEqualsTheKeptOldMethod() {
        Random rnd = new Random(5);
        for (int i = 0; i < 500; i++) {
            double vx = (rnd.nextDouble() - 0.5), vz = (rnd.nextDouble() - 0.5);
            long ticks = rnd.nextInt(14);
            double ex = (rnd.nextDouble() - 0.5) * 14, ey = (rnd.nextDouble() - 0.5) * 8, ez = (rnd.nextDouble() - 0.5) * 14;
            assertEquals(legacy070(ex, ey, ez, vx, vz, ticks), MovementReach.worstRawDamage(ex, ey, ez, vx, vz, ticks), 0f);
        }
    }

    @Test
    void aMovingPlayerIgnoresCoverThatWouldLowerTheResult() {
        float lowered = b2(3, 0.5, 0, 0.3, 0, 6, o -> 0.0, 0f);
        float moving = MovementReach.worstReachRaw(3, 0.5, 0, 0.3, 0, 6, o -> 0.0, 0f);
        assertEquals(legacy070(3, 0.5, 0, 0.3, 0, 6), moving, 0f);
        assertTrue(moving > lowered, "0.7.0's value " + moving + " must exceed the covered B2 value " + lowered);
    }

    @Test
    void atOrBelowTheStillRadiusTheResultIsTodaysRealExposurePath() {
        Random rnd = new Random(99);
        int checked = 0;
        int lower = 0;
        for (int i = 0; i < 3000; i++) {
            long ticks = 1 + rnd.nextInt(12);
            double speed = rnd.nextDouble() * (MovementReach.STILL_RADIUS / ticks);
            double angle = rnd.nextDouble() * 2 * Math.PI;
            double vx = speed * Math.cos(angle), vz = speed * Math.sin(angle);
            if (!MovementReach.readsRealExposure(vx, vz, ticks)) continue;
            double ex = (rnd.nextDouble() - 0.5) * 14, ey = (rnd.nextDouble() - 0.5) * 8, ez = (rnd.nextDouble() - 0.5) * 14;
            float floor = rnd.nextFloat() * 3;
            double cover = rnd.nextDouble();
            MovementReach.ExposureFunction e = o -> Math.abs(o.dx() * 7 + o.dz() * 3) % 1.0 < 0.5 ? cover : 1.0;
            float want = b2(ex, ey, ez, vx, vz, ticks, e, floor);
            assertEquals(want, MovementReach.worstReachRaw(ex, ey, ez, vx, vz, ticks, e, floor), 0f);
            if (want < Math.max(floor, legacy070(ex, ey, ez, vx, vz, ticks))) lower++;
            checked++;
        }
        assertTrue(checked > 1000);
        assertTrue(lower > 50, "real exposure must be able to read lower than 0.7.0 here: " + lower);
    }

    @Test
    void standingStillIsTheRealExposurePath() {
        MovementReach.ExposureFunction e = covered(0.25);
        assertEquals(b2(4, 0.2, 1, 0, 0, 8, e, 0f), MovementReach.worstReachRaw(4, 0.2, 1, 0, 0, 8, e, 0f), 0f);
    }

    @Test
    void theBoundaryRadiusExactlyOneReadsRealExposureJustAboveDoesNot() {
        // speed 0.125 * 8 ticks = exactly 1.0 (exact in binary)
        assertEquals(1.0, MovementReach.reachRadius(0.125, 0, 8), 0.0);
        assertTrue(MovementReach.readsRealExposure(0.125, 0, 8));
        assertFalse(MovementReach.readsRealExposure(Math.nextUp(0.125), 0, 8));
        AtomicInteger askedAt = new AtomicInteger();
        MovementReach.worstReachRaw(3, 0.5, 0, 0.125, 0, 8, o -> {
            askedAt.incrementAndGet();
            return 0.0;
        }, 0f);
        assertTrue(askedAt.get() > 0, "at exactly 1.0 the exposure is read");
        AtomicInteger askedAbove = new AtomicInteger();
        float above = MovementReach.worstReachRaw(3, 0.5, 0, Math.nextUp(0.125), 0, 8, o -> {
            askedAbove.incrementAndGet();
            return 0.0;
        }, 0f);
        assertEquals(0, askedAbove.get());
        assertEquals(legacy070(3, 0.5, 0, Math.nextUp(0.125), 0, 8), above, 0f);
    }

    @Test
    void theRadiusIsTheRingRadiusOfTheOffsets() {
        Random rnd = new Random(3);
        for (int i = 0; i < 200; i++) {
            double vx = (rnd.nextDouble() - 0.5), vz = (rnd.nextDouble() - 0.5);
            long ticks = 1 + rnd.nextInt(12);
            double r = MovementReach.reachRadius(vx, vz, ticks);
            var all = MovementReach.offsets(vx, vz, ticks);
            // per height: (0,h,0), the projection, then the 8 ring points
            for (Offset o : all.subList(2, 10)) {
                assertEquals(r, Math.hypot(o.dx(), o.dz()), 1e-9);
            }
        }
    }

    @Test
    void badInputKeepsEveryProtectionOfEitherBranch() {
        MovementReach.ExposureFunction e = o -> 1.0;
        assertTrue(MovementReach.readsRealExposure(Double.NaN, Double.POSITIVE_INFINITY, 5));
        assertTrue(MovementReach.readsRealExposure(0.5, 0.5, -3));
        assertTrue(Float.isNaN(MovementReach.worstReachRaw(Double.NaN, 0, 0, 0.5, 0, 10, e, 0f)));
        assertTrue(Float.isNaN(MovementReach.worstReachRaw(Double.NaN, 0, 0, 0, 0, 10, e, 0f)));
        float huge = MovementReach.worstReachRaw(2, 0, 0, 0.3, 0, Long.MAX_VALUE, e, 0f);
        assertEquals(legacy070(2, 0, 0, 0.3, 0, Long.MAX_VALUE), huge, 0f);
        assertEquals(7f, MovementReach.worstReachRaw(50, 0, 0, 0.5, 0, 10, e, 7f), 0f);
        assertEquals(7f, MovementReach.worstReachRaw(50, 0, 0, 0, 0, 10, e, 7f), 0f);
    }
}
