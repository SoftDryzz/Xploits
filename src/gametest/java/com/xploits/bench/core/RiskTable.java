package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The report's risk table (crystal-aura++ R2-5): for each Meteor scenario the {@code capp} pairs are judged
 * against, the medians of {@link #METRICS} for Meteor's crystal-aura and for crystal-aura++ at each benched
 * level ({@link #LEVELS}), side by side. Every number stands in its own cell, so no line holds numbers in a
 * row the hygiene scan could take for a position ({@link PositionLike}). Pure.
 */
public final class RiskTable {
    /** The metrics shown, each over Meteor and every level. */
    public static final List<String> METRICS = List.of(Acceptance.DAMAGE_DEALT, Acceptance.MIN_HEALTH);
    /** The levels the bench measures; Custom is whatever the player sets, so it is never benched. */
    public static final List<RiskLevel> LEVELS = List.of(RiskLevel.SAFE, RiskLevel.BALANCED, RiskLevel.AGGRESSIVE);
    /** The column of Meteor's crystal-aura. */
    public static final String METEOR = "Meteor";

    private RiskTable() {
    }

    /**
     * One Meteor scenario and the medians of every side that ran against it; a side or a metric that is
     * missing shows as {@code -}.
     *
     * @param scenario the Meteor scenario's name
     * @param meteor   Meteor's medians, by metric
     * @param levels   crystal-aura++'s medians at each level, by metric
     */
    public record Row(String scenario, Map<String, Double> meteor, Map<RiskLevel, Map<String, Double>> levels) {
        public Row {
            Objects.requireNonNull(scenario, "scenario");
            meteor = Map.copyOf(meteor);
            levels = Map.copyOf(levels);
        }
    }

    /** The table's markdown lines, the header first; none without a row. */
    public static List<String> lines(List<Row> rows) {
        List<String> lines = new ArrayList<>();
        if (rows.isEmpty()) return lines;
        StringBuilder header = new StringBuilder("| Scenario");
        StringBuilder rule = new StringBuilder("|---");
        for (String metric : METRICS) {
            header.append(" | ").append(metric).append(' ').append(METEOR);
            rule.append("|---");
            for (RiskLevel level : LEVELS) {
                header.append(" | ").append(metric).append(' ').append(level);
                rule.append("|---");
            }
        }
        lines.add(header.append(" |").toString());
        lines.add(rule.append('|').toString());
        for (Row row : rows) {
            StringBuilder line = new StringBuilder("| ").append(row.scenario());
            for (String metric : METRICS) {
                line.append(" | ").append(number(row.meteor().get(metric)));
                for (RiskLevel level : LEVELS) {
                    Map<String, Double> medians = row.levels().get(level);
                    line.append(" | ").append(number(medians == null ? null : medians.get(metric)));
                }
            }
            lines.add(line.append(" |").toString());
        }
        return lines;
    }

    private static String number(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }
}
