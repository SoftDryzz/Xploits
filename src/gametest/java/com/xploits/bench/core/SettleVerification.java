package com.xploits.bench.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The proof that a settled run could not have changed (crystal-aura++ R3-6, {@code -Pbench.verifySettle}): the run
 * does not end when it settles. It snapshots its metrics at the first settled tick, runs on to its full length,
 * and its final metrics must equal the snapshot. It must also stay settled for the rest of the run: a tick that is
 * no longer settled after the snapshot means something happened although the run had settled.
 *
 * <p>Fed {@link Settle}'s verdict once per tick after T0. It arms on the first settled tick only; a later settle,
 * after the run stopped being settled or not, never re-arms it nor replaces the snapshot. Pure and deterministic.
 */
public final class SettleVerification {
    private int settledAt = -1;
    private int lostAt = -1;
    private Map<String, Double> snapshot;

    /**
     * A count per second over the run's nominal length, never over the time actually run: what
     * {@code placements_per_s} is, at the settle and at the end alike.
     */
    public static double perNominalSecond(int count, int nominalSeconds) {
        if (nominalSeconds <= 0) throw new IllegalArgumentException("a run lasts at least a second");
        return (double) count / nominalSeconds;
    }

    /**
     * One tick's verdict, {@code tick} ticks after T0. Returns true only on the first settled tick: the caller
     * snapshots the metrics then ({@link #snapshot}). After that, the first tick that is not settled is kept.
     */
    public boolean tick(int tick, boolean settled) {
        if (settledAt < 0) {
            if (!settled) return false;
            settledAt = tick;
            return true;
        }
        if (!settled && lostAt < 0) lostAt = tick;
        return false;
    }

    /** The metrics at the settle; once, and only after {@link #tick} armed it. */
    public void snapshot(Map<String, Double> metrics) {
        Objects.requireNonNull(metrics, "metrics");
        if (settledAt < 0) throw new IllegalStateException("the run has not settled");
        if (snapshot != null) throw new IllegalStateException("the settle was already snapshotted");
        snapshot = Map.copyOf(metrics);
    }

    /** Ticks after T0 of the first settle; -1 when the run never settled. */
    public int settledAt() {
        return settledAt;
    }

    /** Ticks after T0 of the first tick after the settle that was not settled; -1 when there was none. */
    public int lostAt() {
        return lostAt;
    }

    /**
     * What went wrong, given the final metrics; empty when the run never settled, or settled and nothing changed.
     * Each metric that differs (exactly: they are the same sums over the same events) is named with both values,
     * and so is one missing on either side; a lost settle is named with its tick.
     */
    public List<String> problems(Map<String, Double> atEnd) {
        Objects.requireNonNull(atEnd, "atEnd");
        List<String> problems = new ArrayList<>();
        if (settledAt < 0) return problems;
        if (snapshot == null) throw new IllegalStateException("the settle was never snapshotted");
        Set<String> names = new LinkedHashSet<>(snapshot.keySet());
        names.addAll(atEnd.keySet());
        for (String name : names.stream().sorted().toList()) {
            Double before = snapshot.get(name);
            Double after = atEnd.get(name);
            if (before == null || after == null || Double.compare(before, after) != 0) {
                problems.add(name + " was " + show(before) + " at the settle and " + show(after) + " at the end");
            }
        }
        if (lostAt >= 0) {
            problems.add("the run was no longer settled " + (lostAt - settledAt) + " tick(s) after it settled");
        }
        return problems;
    }

    private static String show(Double value) {
        return value == null ? "absent" : String.format(Locale.ROOT, "%.6f", value);
    }
}
