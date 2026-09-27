package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Pbench.verifySettle} (R3-6): a settled run runs on to its full length, and its final metrics must equal
 * the snapshot taken at the first settle, with placements always per nominal second.
 */
class SettleVerificationTest {
    private static Map<String, Double> metrics(double damage, double placements) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("damage_dealt", damage);
        m.put("placements_per_s", placements);
        return m;
    }

    /** Settled from {@code at} on, snapshotted there, and settled every tick up to {@code end}. */
    private static SettleVerification settledFrom(int at, int end, Map<String, Double> snapshot) {
        SettleVerification v = new SettleVerification();
        for (int tick = 1; tick < at; tick++) assertFalse(v.tick(tick, false));
        assertTrue(v.tick(at, true));
        v.snapshot(snapshot);
        for (int tick = at + 1; tick <= end; tick++) assertFalse(v.tick(tick, true));
        return v;
    }

    @Test
    void placementsAreCountedOverTheNominalLength() {
        assertEquals(14 / 30.0, SettleVerification.perNominalSecond(14, 30), 1e-12);
        assertEquals(0.0, SettleVerification.perNominalSecond(0, 30), 0);
        assertThrows(IllegalArgumentException.class, () -> SettleVerification.perNominalSecond(3, 0));
    }

    @Test
    void identicalMetricsPass() {
        SettleVerification v = settledFrom(60, 600, metrics(30, SettleVerification.perNominalSecond(14, 30)));
        assertEquals(60, v.settledAt());
        assertEquals(-1, v.lostAt());
        assertEquals(List.of(), v.problems(metrics(30, SettleVerification.perNominalSecond(14, 30))));
    }

    @Test
    void aMetricThatChangedIsNamedWithBothValues() {
        SettleVerification v = settledFrom(60, 600, metrics(30, 0.4));
        List<String> problems = v.problems(metrics(30.5, 0.4));
        assertEquals(List.of("damage_dealt was 30.000000 at the settle and 30.500000 at the end"), problems);
    }

    @Test
    void theSmallestDifferenceCounts() {
        SettleVerification v = settledFrom(60, 600, metrics(30, 0.4));
        assertEquals(1, v.problems(metrics(Math.nextUp(30.0), 0.4)).size());
    }

    @Test
    void aMetricThatAppearsOrDisappearsIsNamed() {
        SettleVerification v = settledFrom(60, 600, metrics(30, 0.4));
        Map<String, Double> popped = metrics(30, 0.4);
        popped.put("first_pop_s", 12.5);
        assertEquals(List.of("first_pop_s was absent at the settle and 12.500000 at the end"), v.problems(popped));

        SettleVerification w = settledFrom(60, 600, popped);
        assertEquals(List.of("first_pop_s was 12.500000 at the settle and absent at the end"), w.problems(metrics(30, 0.4)));
    }

    @Test
    void itArmsOnceAtTheFirstSettleAndALaterSettleNeverReArmsIt() {
        SettleVerification v = new SettleVerification();
        assertFalse(v.tick(1, false));
        assertTrue(v.tick(80, true));
        v.snapshot(metrics(30, 0.4));
        assertFalse(v.tick(81, false), "losing the settle does not arm it");
        assertFalse(v.tick(200, true), "settling again does not re-arm it");
        assertEquals(80, v.settledAt());
        assertEquals(81, v.lostAt());
        assertThrows(IllegalStateException.class, () -> v.snapshot(metrics(31, 0.4)), "the snapshot is taken once");
        // The metrics compare against the first snapshot, and the lost settle is a problem of its own.
        assertEquals(List.of("the run was no longer settled 1 tick(s) after it settled"), v.problems(metrics(30, 0.4)));
    }

    @Test
    void theFirstLostTickIsKept() {
        SettleVerification v = settledFrom(60, 100, metrics(30, 0.4));
        v.tick(101, false);
        v.tick(150, false);
        assertEquals(101, v.lostAt());
    }

    @Test
    void aRunThatNeverSettledHasNothingToVerify() {
        SettleVerification v = new SettleVerification();
        for (int tick = 1; tick <= 600; tick++) assertFalse(v.tick(tick, false));
        assertEquals(-1, v.settledAt());
        assertEquals(List.of(), v.problems(metrics(1, 2)));
        assertThrows(IllegalStateException.class, () -> v.snapshot(metrics(1, 2)), "no snapshot before a settle");
    }

    @Test
    void aSettleWithoutItsSnapshotIsAnError() {
        SettleVerification v = new SettleVerification();
        v.tick(60, true);
        assertThrows(IllegalStateException.class, () -> v.problems(metrics(1, 2)));
    }
}
