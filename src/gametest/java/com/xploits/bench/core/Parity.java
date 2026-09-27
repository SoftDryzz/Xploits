package com.xploits.bench.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The CHECK {@code capp-budget-off-parity} (crystal-aura++ R2-4): crystal-aura++ with {@code self-budget} off
 * against Meteor's crystal-aura on the same arena, {@value #RUNS} runs each. With the budget off ++ is Meteor's
 * offense copied, so each compared metric's median must match Meteor's within the larger of its noise floor and
 * {@value Acceptance#SHARE_PERCENT} % of Meteor's value, in either direction. A gap beyond that means the copy
 * is not faithful, and then a difference the {@code capp-*} pairs show could come from the copy and not only
 * from the budget. Pure: the CHECK hands in the runs' numbers and the floors of the bench's metric table.
 */
public final class Parity {
    /** The runs each side needs. */
    public static final int RUNS = Acceptance.RUNS;
    /** The metrics compared, in the order the report shows them. */
    public static final List<String> METRICS = List.of(Acceptance.DAMAGE_DEALT, Acceptance.SPARRING_POPS,
        Acceptance.SELF_DAMAGE, Acceptance.MIN_HEALTH, Acceptance.PLACEMENTS_PER_S);

    /**
     * One metric compared: both medians, the gap allowed, and whether the gap is within it.
     */
    public record Metric(String name, double plusPlus, double meteor, double allowed, boolean within) {
        /** In words, never three numbers in a row (the report hygiene). */
        public String detail() {
            return "median " + name + ": ++ " + n(plusPlus) + ", Meteor " + n(meteor) + ", allowed gap " + n(allowed)
                + (within ? "" : ", too far");
        }
    }

    /** Whether every metric matches, and each comparison in {@link #METRICS} order. */
    public record Outcome(boolean matches, List<Metric> metrics) {
        public Outcome {
            metrics = List.copyOf(metrics);
        }

        /** The metrics that do not match, by name. */
        public List<String> failed() {
            return metrics.stream().filter(m -> !m.within()).map(Metric::name).toList();
        }
    }

    private Parity() {
    }

    /**
     * Compares the two sides.
     *
     * @param plusPlus crystal-aura++'s runs, with the budget off
     * @param meteor   Meteor's runs
     * @param floors   each compared metric's noise floor (the bench's metric table)
     * @throws IllegalArgumentException when a side has not exactly {@value #RUNS} runs, a run lacks a metric, or
     *                                  a floor is missing: that is not a result
     */
    public static Outcome judge(List<Map<String, Double>> plusPlus, List<Map<String, Double>> meteor,
                                Map<String, Double> floors) {
        check(plusPlus, "crystal-aura++");
        check(meteor, "Meteor");
        List<Metric> metrics = new ArrayList<>();
        for (String name : METRICS) {
            Double floor = floors.get(name);
            if (floor == null || !(floor >= 0)) throw new IllegalArgumentException("no noise floor for " + name);
            double ours = median(plusPlus, name);
            double theirs = median(meteor, name);
            double allowed = Acceptance.margin(theirs, floor);
            metrics.add(new Metric(name, ours, theirs, allowed, Math.abs(ours - theirs) <= allowed));
        }
        return new Outcome(metrics.stream().allMatch(Metric::within), metrics);
    }

    private static void check(List<Map<String, Double>> runs, String side) {
        Objects.requireNonNull(runs, side);
        if (runs.size() != RUNS) throw new IllegalArgumentException(side + " has " + runs.size() + " runs, not " + RUNS);
        for (Map<String, Double> run : runs) {
            for (String name : METRICS) {
                Double v = run.get(name);
                if (v == null || !Double.isFinite(v)) throw new IllegalArgumentException("a run of " + side + " has no " + name);
            }
        }
    }

    private static double median(List<Map<String, Double>> runs, String name) {
        return Acceptance.median(runs.stream().map(run -> run.get(name)).toList());
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
