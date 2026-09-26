package com.xploits.bench;

/**
 * One bench scenario (spec {@code 2026-09-25-ingame-bench}, §Code layout). Every run of it gets a fresh
 * world and a fresh {@link Bench}; {@link BenchTest} runs {@link #arrange} then {@link #act}, and the
 * teardown after them, even when they throw.
 *
 * <p>A CHECK throws {@link AssertionError} when it fails (use {@link Bench#check}); a MEASURE returns its
 * {@link Metrics}. Anything else thrown ends the run as ERROR.
 */
public interface Scenario {
    enum Kind {
        /** Pass or fail, run once; a failure blocks the release. */
        CHECK,
        /** Numbers, run {@value Scenario#MEASURE_RUNS} times, each in its own world. */
        MEASURE
    }

    /** How many runs a MEASURE gets. */
    int MEASURE_RUNS = 3;

    /** The name used in the report and by {@code -Pbench.only}. */
    String name();

    Kind kind();

    /** How long {@link #act} runs after T0, in seconds. */
    int seconds();

    /** Every tick a run may wait, from the first arrange step to the last act step; past it the run is ERROR. */
    default int budgetTicks() {
        return seconds() * 20 + 400;
    }

    default int runs() {
        return kind() == Kind.CHECK ? 1 : MEASURE_RUNS;
    }

    /** Builds the scene after the common preparation ({@link Bench#prepare}); everything before T0. */
    void arrange(Bench bench);

    /**
     * T0 ({@link Bench#start}), the scenario's own steps, and the close ({@link Bench#finish}). A CHECK
     * returns {@link Metrics#none()}.
     */
    Metrics act(Bench bench);
}
