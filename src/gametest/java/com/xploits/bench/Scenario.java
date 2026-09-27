package com.xploits.bench;

import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.Optional;

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

    /**
     * The MEASURE this one is judged against in the same invocation (crystal-aura++ spec §4: {@code capp-X}
     * against {@code ca-X}), or empty. The verdict goes to the report and never fails the bench by itself.
     */
    default Optional<String> compareWith() {
        return Optional.empty();
    }

    /**
     * The {@code risk} level crystal-aura++ runs at in this scenario (R2-5), or empty when it is not
     * crystal-aura++'s. Each level gets its own recommendation line.
     */
    default Optional<RiskLevel> risk() {
        return Optional.empty();
    }

    /**
     * Whether the run's world keeps natural health regeneration on: only the healing MEASUREs do
     * (crystal-aura++ spec, Round 2 (b)); every other scenario runs without it ({@link Bench#prepare}).
     */
    default boolean naturalRegeneration() {
        return false;
    }

    /**
     * Whether this run's connection plays over the bench's simulated ping (R3-12, {@code PingDelay}): every
     * crystal-aura MEASURE, {@code ca-*} and {@code capp-*} ({@code CrystalAuraMeasure}), and the CHECK that
     * measures crystal-aura++ without the budget inside it ({@code CappBudgetOffParity}), all identically for
     * both auras. Every other scenario keeps today's lock-step (0 ms): none of them race the tick crystal-aura's
     * exact-damage raycasts can lose, so the delay is not needed for them to stay valid.
     */
    default boolean simulatesPing() {
        return false;
    }

    /** Builds the scene after the common preparation ({@link Bench#prepare}); everything before T0. */
    void arrange(Bench bench);

    /**
     * T0 ({@link Bench#start}), the scenario's own steps, and the close ({@link Bench#finish}). A CHECK
     * returns {@link Metrics#none()}, or the numbers of the run it judges, which the report lists with the run.
     */
    Metrics act(Bench bench);
}
