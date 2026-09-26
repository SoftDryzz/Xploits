package com.xploits.bench.core;

import com.xploits.bench.core.RiskTable.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.xploits.pvp.crystal.core.RiskLevel.AGGRESSIVE;
import static com.xploits.pvp.crystal.core.RiskLevel.BALANCED;
import static com.xploits.pvp.crystal.core.RiskLevel.CUSTOM;
import static com.xploits.pvp.crystal.core.RiskLevel.SAFE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The report's risk table (R2-5): damage dealt and min health, Meteor then each level, one number per cell. */
class RiskTableTest {
    private static Map<String, Double> medians(double damage, double minHealth) {
        return Map.of(Acceptance.DAMAGE_DEALT, damage, Acceptance.MIN_HEALTH, minHealth);
    }

    @Test
    void aRowHoldsMeteorThenEachLevelForEachMetric() {
        List<String> lines = RiskTable.lines(List.of(new Row("ca-still", medians(30, 3.37),
            Map.of(SAFE, medians(20, 8.92), BALANCED, medians(25, 5.5), AGGRESSIVE, medians(30, 3.25)))));
        assertEquals(List.of(
                "| Scenario | damage_dealt Meteor | damage_dealt Safe | damage_dealt Balanced | damage_dealt Aggressive"
                    + " | min_health Meteor | min_health Safe | min_health Balanced | min_health Aggressive |",
                "|---|---|---|---|---|---|---|---|---|",
                "| ca-still | 30.00 | 20.00 | 25.00 | 30.00 | 3.37 | 8.92 | 5.50 | 3.25 |"),
            lines);
    }

    @Test
    void aSideOrAMetricThatDidNotRunIsADash() {
        // Only Safe ran against ca-defender, and Meteor's side lacks min_health.
        List<String> lines = RiskTable.lines(List.of(new Row("ca-defender", Map.of(Acceptance.DAMAGE_DEALT, 0.0),
            Map.of(SAFE, medians(0, 20)))));
        assertEquals("| ca-defender | 0.00 | 0.00 | - | - | - | 20.00 | - | - |", lines.get(2));
    }

    @Test
    void rowsKeepTheirOrderAndCustomIsNeverAColumn() {
        List<String> lines = RiskTable.lines(List.of(
            new Row("ca-still", medians(30, 3), Map.of(CUSTOM, medians(1, 1))),
            new Row("ca-circler-regen", medians(20, 4), Map.of())));
        assertEquals(4, lines.size());
        assertEquals("| ca-still | 30.00 | - | - | - | 3.00 | - | - | - |", lines.get(2));
        assertEquals("| ca-circler-regen | 20.00 | - | - | - | 4.00 | - | - | - |", lines.get(3));
        assertFalse(lines.getFirst().contains("Custom"));
    }

    @Test
    void noRowNoTable() {
        assertEquals(List.of(), RiskTable.lines(List.of()));
    }

    @Test
    void everyNumberStandsInItsOwnCellAndNoLineLooksLikeAPosition() {
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            List<String> lines = RiskTable.lines(List.of(new Row("ca-still", medians(r.nextDouble() * 40, r.nextDouble() * 20),
                Map.of(SAFE, medians(r.nextDouble() * 40, r.nextDouble() * 20),
                    BALANCED, medians(r.nextDouble() * 40, r.nextDouble() * 20),
                    AGGRESSIVE, medians(r.nextDouble() * 40, r.nextDouble() * 20)))));
            for (String line : lines) assertFalse(PositionLike.in(line), line);
            // A scenario cell and 8 number cells.
            assertEquals(9, lines.get(2).split(" \\| ").length, lines.get(2));
        }
    }
}
