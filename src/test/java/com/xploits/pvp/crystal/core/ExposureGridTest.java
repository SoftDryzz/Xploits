package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vanilla's sample grid (yarn 1.21.11 {@code ExplosionImpl.calculateReceivedDamage}) for a player-sized box. */
class ExposureGridTest {
    private static final double EPS = 1e-12;

    @Test
    void aPlayerBoxHasThreeByFiveByThreePoints() {
        // 0.6 x 1.8 x 0.6: steps 1/2.2, 1/4.6, 1/2.2 -> 3, 5, 3 points per axis.
        assertEquals(45 * 3, ExposureGrid.samples(0, 0, 0, 0.6, 1.8, 0.6).length);
    }

    @Test
    void theGridIsCentredOnXAndZButNotOnY() {
        double[] s = ExposureGrid.samples(0, 0, 0, 0.6, 1.8, 0.6);
        double step = 1.0 / 2.2;
        double off = (1.0 - Math.floor(1.0 / step) * step) / 2.0;
        assertEquals(0 + off, s[0], EPS);
        assertEquals(0.0, s[1], 0.0, "y has no nudge");
        assertEquals(0 + off, s[2], EPS);
        int last = s.length - 3;
        assertEquals(2 * step * 0.6 + off, s[last], EPS);
        assertEquals(4 * (1.0 / 4.6) * 1.8, s[last + 1], EPS);
        assertEquals(2 * step * 0.6 + off, s[last + 2], EPS);
    }

    @Test
    void theOrderIsXOutermostThenYThenZ() {
        double[] s = ExposureGrid.samples(0, 0, 0, 0.6, 1.8, 0.6);
        // Second point differs from the first only in z; the fourth (after 3 z) only in y.
        assertEquals(s[0], s[3], 0.0);
        assertEquals(s[1], s[4], 0.0);
        assertTrue(s[5] > s[2]);
        assertEquals(s[0], s[9], 0.0);
        assertTrue(s[10] > s[1]);
    }

    @Test
    void translatingTheBoxTranslatesEveryPoint() {
        double[] a = ExposureGrid.samples(0, 0, 0, 0.5, 2.0, 0.5);
        double[] b = ExposureGrid.samples(4, 8, -2, 4.5, 10.0, -1.5);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i += 3) {
            assertEquals(a[i] + 4, b[i], 1e-9);
            assertEquals(a[i + 1] + 8, b[i + 1], 1e-9);
            assertEquals(a[i + 2] - 2, b[i + 2], 1e-9);
        }
    }

    @Test
    void aBoxWithNoSaneSizeHasNoPoints() {
        assertArrayEquals(new double[0], ExposureGrid.samples(0, 0, 0, -1, 1, 1));
        assertArrayEquals(new double[0], ExposureGrid.samples(0, 0, 0, Double.NaN, 1, 1));
        assertArrayEquals(new double[0], ExposureGrid.samples(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1));
        assertArrayEquals(new double[0], ExposureGrid.samples(0, 0, 0, 1e6, 1, 1));
    }
}
