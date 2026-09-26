package com.xploits.bench.core;

import com.xploits.bench.core.Parity.Metric;
import com.xploits.bench.core.Parity.Outcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CHECK {@code capp-budget-off-parity}'s comparison: medians within max(noise floor, 15 % of Meteor's), both
 * ways, at the exact edge of each branch; the runs it needs; and texts the report hygiene accepts.
 */
class ParityTest {
    /** The bench's noise floors for the compared metrics (its Metrics table). */
    private static final Map<String, Double> FLOORS = Map.of(Acceptance.DAMAGE_DEALT, 1.0, Acceptance.SPARRING_POPS, 1.0,
        Acceptance.SELF_DAMAGE, 1.0, Acceptance.MIN_HEALTH, 1.0, Acceptance.PLACEMENTS_PER_S, 0.3);

    private static Map<String, Double> run(double damage, double pops, double self, double minHealth, double placements) {
        Map<String, Double> m = new HashMap<>();
        m.put(Acceptance.DAMAGE_DEALT, damage);
        m.put(Acceptance.SPARRING_POPS, pops);
        m.put(Acceptance.SELF_DAMAGE, self);
        m.put(Acceptance.MIN_HEALTH, minHealth);
        m.put(Acceptance.PLACEMENTS_PER_S, placements);
        return m;
    }

    /** Meteor on the still arena, as the bench measured it. */
    private static Map<String, Double> meteor() {
        return run(30, 2, 16.625, 3.375, 0.5);
    }

    private static List<Map<String, Double>> three(Map<String, Double> run) {
        return List.of(run, new HashMap<>(run), new HashMap<>(run));
    }

    private static Outcome judge(Map<String, Double> plusPlus) {
        return Parity.judge(three(plusPlus), three(meteor()), FLOORS);
    }

    private static Metric metric(Outcome o, String name) {
        return o.metrics().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theSameNumbersMatch() {
        Outcome o = judge(meteor());
        assertTrue(o.matches());
        assertEquals(Parity.METRICS, o.metrics().stream().map(Metric::name).toList());
        assertEquals(List.of(), o.failed());
    }

    @Test
    void fifteenPercentOfMeteorsIsTheGapWhenItIsLarger() {
        // Damage 30: 15 % is 4.5 > the floor 1. 25.5 and 34.5 are at the edge; a quarter further is not.
        assertTrue(judge(run(25.5, 2, 16.625, 3.375, 0.5)).matches());
        assertTrue(judge(run(34.5, 2, 16.625, 3.375, 0.5)).matches());
        Outcome low = judge(run(25.25, 2, 16.625, 3.375, 0.5));
        assertFalse(low.matches());
        assertEquals(List.of(Acceptance.DAMAGE_DEALT), low.failed());
        assertEquals(4.5, metric(low, Acceptance.DAMAGE_DEALT).allowed(), 0.0);
        assertFalse(judge(run(34.75, 2, 16.625, 3.375, 0.5)).matches());
    }

    @Test
    void theNoiseFloorIsTheGapWhenItIsLarger() {
        // Min health 3.375: 15 % is about 0.51 < the floor 1. 4.375 is at the edge, 4.5 beyond.
        assertTrue(judge(run(30, 2, 16.625, 4.375, 0.5)).matches());
        assertTrue(judge(run(30, 2, 16.625, 2.375, 0.5)).matches());
        assertEquals(List.of(Acceptance.MIN_HEALTH), judge(run(30, 2, 16.625, 4.5, 0.5)).failed());
        // Placements 0.5: the floor 0.3 wins over 0.075.
        assertTrue(judge(run(30, 2, 16.625, 3.375, 0.75)).matches());
        assertEquals(List.of(Acceptance.PLACEMENTS_PER_S), judge(run(30, 2, 16.625, 3.375, 0.875)).failed());
        // Pops 2: one pop either way is noise, two are not.
        assertTrue(judge(run(30, 3, 16.625, 3.375, 0.5)).matches());
        assertTrue(judge(run(30, 1, 16.625, 3.375, 0.5)).matches());
        assertEquals(List.of(Acceptance.SPARRING_POPS), judge(run(30, 0, 16.625, 3.375, 0.5)).failed());
    }

    @Test
    void aGapEitherWayFails() {
        // Less self damage is better for safety but still not Meteor's: with the budget off it must be the same.
        assertEquals(List.of(Acceptance.SELF_DAMAGE), judge(run(30, 2, 12, 3.375, 0.5)).failed());
        assertEquals(List.of(Acceptance.SELF_DAMAGE), judge(run(30, 2, 20, 3.375, 0.5)).failed());
    }

    @Test
    void everyMetricThatFailsIsNamedInOrder() {
        assertEquals(List.of(Acceptance.DAMAGE_DEALT, Acceptance.MIN_HEALTH),
            judge(run(20, 2, 16.625, 8, 0.5)).failed());
    }

    @Test
    void mediansNotMeans() {
        // One wild run on each side does not move a median.
        List<Map<String, Double>> ours = List.of(meteor(), meteor(), run(0, 0, 0, 20, 0));
        List<Map<String, Double>> theirs = List.of(run(90, 6, 40, 0.5, 2), meteor(), meteor());
        assertTrue(Parity.judge(ours, theirs, FLOORS).matches());
    }

    @Test
    void anythingButThreeFullRunsIsNotAResult() {
        assertThrows(IllegalArgumentException.class, () -> Parity.judge(List.of(meteor(), meteor()), three(meteor()), FLOORS));
        assertThrows(IllegalArgumentException.class, () -> Parity.judge(three(meteor()), List.of(), FLOORS));
        Map<String, Double> missing = meteor();
        missing.remove(Acceptance.MIN_HEALTH);
        assertThrows(IllegalArgumentException.class, () -> Parity.judge(three(missing), three(meteor()), FLOORS));
        Map<String, Double> floors = new HashMap<>(FLOORS);
        floors.remove(Acceptance.SELF_DAMAGE);
        assertThrows(IllegalArgumentException.class, () -> Parity.judge(three(meteor()), three(meteor()), floors));
    }

    @Test
    void theDetailsNeverLookLikeAPosition() {
        for (double a = 0; a <= 40; a += 0.75) {
            Outcome o = judge(run(a, a / 10, a, a / 2, a / 20));
            for (Metric m : o.metrics()) assertFalse(PositionLike.in(m.detail()), m.detail());
        }
        assertEquals("median damage_dealt: ++ 25.25, Meteor 30.00, allowed gap 4.50, too far",
            metric(judge(run(25.25, 2, 16.625, 3.375, 0.5)), Acceptance.DAMAGE_DEALT).detail());
    }

    @Test
    void theMetricsAreTheBenchReportsNames() throws IOException {
        // The bench's Metrics table is not on the test classpath; its source is read instead.
        String metrics = Files.readString(Path.of("src", "gametest", "java", "com", "xploits", "bench", "Metrics.java"),
            StandardCharsets.UTF_8);
        for (String name : Parity.METRICS) assertTrue(metrics.contains("= \"" + name + "\";"), "Metrics has no " + name);
        assertEquals(List.of("damage_dealt", "sparring_pops", "self_damage", "min_health", "placements_per_s"), Parity.METRICS);
    }
}
