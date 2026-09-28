package com.xploits.bench;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Named numbers from one MEASURE run, in the order they were put, and the table that defines every
 * metric (spec {@code 2026-09-25-ingame-bench}, §Metrics): which direction is better, and the noise
 * floor below which a difference means nothing.
 */
public final class Metrics {
    public static final String DAMAGE_DEALT = "damage_dealt";
    public static final String SPARRING_POPS = "sparring_pops";
    public static final String FIRST_POP_S = "first_pop_s";
    public static final String NO_POP_RUNS = "no_pop_runs";
    public static final String SELF_DAMAGE = "self_damage";
    public static final String SELF_POPS = "self_pops";
    public static final String MIN_HEALTH = "min_health";
    public static final String PLACEMENTS_PER_S = "placements_per_s";
    public static final String DAMAGE_TAKEN = "damage_taken";
    // Task A1 requirement 3: fight-mode-only metrics; old scenarios never put them, and Report/Acceptance
    // (Metrics.TABLE-driven) already tolerate a metric's absence, so no other code needed a change for that.
    /** 1 win (the sparring died first), -1 loss (we died), 0 draw (the time limit). */
    public static final String RESULT = "result";
    /** The sparring's pops this run. */
    public static final String POPS_DEALT = "pops_dealt";
    /** Our own pops this run. */
    public static final String POPS_TAKEN = "pops_taken";
    /** {@code pops_dealt - pops_taken}. */
    public static final String NET_POPS = "net_pops";
    /** Seconds from T0 to our first pop; absent when we never popped. */
    public static final String FIRST_POP_TAKEN_S = "first_pop_taken_s";
    /** The lowest health plus absorption right after a hit from one of our own crystals; absent when we
     * took none. */
    public static final String MIN_HEALTH_AFTER_OWN_HIT = "min_health_after_own_hit";

    /** Which way a metric gets better. */
    public enum Better {
        HIGHER, LOWER
    }

    /**
     * One metric of the spec's table. A {@code perScenario} metric counts runs: each run puts 1 or 0, and
     * the scenario's value (median, min and max alike) is the sum, a count with no spread.
     */
    public record Definition(String name, Better better, double floor, boolean perScenario) {
    }

    /** The spec's metric table, in its order. */
    public static final List<Definition> TABLE = List.of(
        new Definition(DAMAGE_DEALT, Better.HIGHER, 1.0, false),
        new Definition(SPARRING_POPS, Better.HIGHER, 1, false),
        new Definition(FIRST_POP_S, Better.LOWER, 0.25, false),
        new Definition(NO_POP_RUNS, Better.LOWER, 0, true),
        new Definition(SELF_DAMAGE, Better.LOWER, 1.0, false),
        new Definition(SELF_POPS, Better.LOWER, 1, false),
        new Definition(MIN_HEALTH, Better.HIGHER, 1.0, false),
        new Definition(PLACEMENTS_PER_S, Better.HIGHER, 0.3, false),
        new Definition(DAMAGE_TAKEN, Better.LOWER, 1.0, false),
        // RESULT is categorical (-1/0/1: loss/draw/win), not a continuous quantity with a real margin; HIGHER
        // and a 0 floor just give it a sane median/min/max ordering (win > draw > loss) for the report table
        // to sort by, not a claim that "higher" or "0 margin" mean anything the way they do for the other
        // metrics (fix round 1, minor finding 4).
        new Definition(RESULT, Better.HIGHER, 0, false),
        new Definition(POPS_DEALT, Better.HIGHER, 1, false),
        new Definition(POPS_TAKEN, Better.LOWER, 1, false),
        new Definition(NET_POPS, Better.HIGHER, 1, false),
        new Definition(FIRST_POP_TAKEN_S, Better.LOWER, 0.25, false),
        new Definition(MIN_HEALTH_AFTER_OWN_HIT, Better.HIGHER, 1.0, false));

    private static final Map<String, Definition> BY_NAME = TABLE.stream()
        .collect(Collectors.toUnmodifiableMap(Definition::name, Function.identity()));

    private final Map<String, Double> values = new LinkedHashMap<>();

    /** What a CHECK returns: no numbers. */
    public static Metrics none() {
        return new Metrics();
    }

    /** The definition of a metric; ERROR for a name the table does not have. */
    public static Definition definition(String name) {
        Definition definition = BY_NAME.get(name);
        if (definition == null) throw new BenchException("no metric is called " + name);
        return definition;
    }

    /** Puts one of the table's metrics; a name it does not have, or a value that is not a number, is ERROR. */
    public Metrics put(String name, double value) {
        definition(name);
        if (!Double.isFinite(value)) throw new BenchException("the metric " + name + " is not a number");
        values.put(name, value);
        return this;
    }

    public Map<String, Double> values() {
        return Collections.unmodifiableMap(values);
    }
}
