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

    // Task B0b: the finishing blow. Each is only in a run where it happened (absent otherwise).
    /** Override crystals we attacked that exploded (crystal-aura++'s finishing blow), whether or not they hurt us. */
    public static final String FINISHING_BLOWS = "finishing_blows";
    /** The lowest health plus absorption after a finishing hit; above 0 means we were still alive. */
    public static final String MIN_HEALTH_AFTER_FINISHING_HIT = "min_health_after_finishing_hit";
    /** The fewest totems we carried at any finishing hit. */
    public static final String TOTEMS_AT_FINISHING_HIT_MIN = "totems_at_finishing_hit_min";
    /** Finishing blows against a target holding no totem, kill-grade by the bench's reading (crystal-aura++). */
    public static final String FINISHING_KILL_BLOWS = "finishing_kill_blows";
    /** Finishing blows against a target holding a totem, pop-grade by the bench's reading (crystal-aura++). */
    public static final String FINISHING_POP_BLOWS = "finishing_pop_blows";
    /** Finishing blows that HURT us, matched to one of our own damage events (task T2: the safety count; blows are the offense). */
    public static final String FINISHING_HITS = "finishing_hits";
    /** The kill-grade ones among {@link #FINISHING_HITS}. */
    public static final String FINISHING_KILL_HITS = "finishing_kill_hits";
    /** The pop-grade ones among {@link #FINISHING_HITS}. */
    public static final String FINISHING_POP_HITS = "finishing_pop_hits";
    /** Finishing hits that cost us a totem (crystal-aura++). */
    public static final String FINISHING_POPS = "finishing_pops";
    /** Finishing blows against a target holding a totem that left us below 2 or popped us (pop-grade rule). */
    public static final String FINISHING_POP_GRADE_VIOLATIONS = "finishing_pop_grade_violations";
    /** 1 in a run that ended in our death after we had carried at least one totem before the hit. */
    public static final String DIED_WITH_TOTEM = "died_with_totem";
    /** Near-death fights: seconds from the near-death moment to our first kill or pop dealt; absent without one. */
    public static final String FIRST_BLOW_S = "first_blow_s";
    /** Near-death fights: 1 in a run whose warm-up never landed a hit of ours in time (counted per scenario). */
    public static final String WARMUP_MISSED = "warmup_missed";
    /** Near-death fights, crystal-aura++ only: 1 in a run whose target it did not trust at the near-death moment
     * (counted per scenario): the finishing blow could not apply. */
    public static final String TRUST_MISSED = "trust_missed";

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
        new Definition(MIN_HEALTH_AFTER_OWN_HIT, Better.HIGHER, 1.0, false),
        new Definition(FINISHING_BLOWS, Better.HIGHER, 1, false),
        new Definition(MIN_HEALTH_AFTER_FINISHING_HIT, Better.HIGHER, 1.0, false),
        new Definition(TOTEMS_AT_FINISHING_HIT_MIN, Better.HIGHER, 1, false),
        new Definition(FINISHING_KILL_BLOWS, Better.HIGHER, 1, false),
        new Definition(FINISHING_POP_BLOWS, Better.HIGHER, 1, false),
        new Definition(FINISHING_HITS, Better.LOWER, 0, false),
        new Definition(FINISHING_KILL_HITS, Better.LOWER, 0, false),
        new Definition(FINISHING_POP_HITS, Better.LOWER, 0, false),
        new Definition(FINISHING_POPS, Better.LOWER, 0, false),
        new Definition(FINISHING_POP_GRADE_VIOLATIONS, Better.LOWER, 0, false),
        new Definition(DIED_WITH_TOTEM, Better.LOWER, 0, false),
        new Definition(FIRST_BLOW_S, Better.LOWER, 0.25, false),
        new Definition(WARMUP_MISSED, Better.LOWER, 0, true),
        new Definition(TRUST_MISSED, Better.LOWER, 0, true));

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
