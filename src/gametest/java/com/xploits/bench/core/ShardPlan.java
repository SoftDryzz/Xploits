package com.xploits.bench.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Task A5: how {@code -Pbench.shard=k/n} splits the bench's scenarios across up to {@value #MAX_SHARDS}
 * clients, so the owner can run the in-game bench as several Minecraft clients at once and still get one
 * report, indistinguishable in shape and meaning from a single client's ({@link ReportMerge}). Pure.
 *
 * <p>A <b>compare group</b> is a Meteor scenario ({@code ca-X}) together with every scenario judged against
 * it ({@link Item#compareWith}: {@code capp-X}, {@code capp-balanced-X}, {@code capp-aggressive-X}, ...): the
 * group's key is the Meteor scenario's own name, so every item that names it as {@code compareWith}, plus the
 * Meteor scenario itself (whose own {@code compareWith} is absent), share the same key. An item with no
 * compare relation of its own (a CHECK, or a MEASURE with no crystal-aura++ twin) is a group of one. Groups
 * are never split across shards: a shard that holds a {@code capp-X} pair always holds its {@code ca-X} twin
 * too, so its compare verdicts ({@code Acceptance.judge}) are exactly what a single, unsharded run would give.
 *
 * <p><b>Balance.</b> Each item's estimated duration is {@code runs * (timeLimitSeconds + OVERHEAD_SECONDS_PER_RUN)}:
 * every run gets a fresh world, and {@value #OVERHEAD_SECONDS_PER_RUN} s per run stands for that world's
 * creation and teardown — the same fixed term {@code Scenario.budgetTicks()} already adds to every run's own
 * wait budget (400 ticks at 20 ticks/s). A group's duration is the sum of its items'. Groups are then handed
 * out with list scheduling (assign the next group to the shard whose running total is currently smallest):
 * whatever order the groups are considered in, once every group is assigned, the busiest shard's total minus
 * the least busy shard's is at most the duration of the single biggest group (the standard guarantee of
 * "always add to the least loaded machine": right before its own last group was added, the busiest shard's
 * total was less than or equal to every other shard's, since that is why it was chosen). Groups are
 * considered largest first (LPT), which tightens that bound further but changes nothing about the guarantee.
 * Ties (equal group duration, or equal shard total) break on the group's, or the shard's, own index, so the
 * plan never depends on hash order or on which JVM ran it.
 *
 * <p>Each shard's own list keeps the items' original relative order (the order {@code items} was given in,
 * which the bench always builds from {@code Scenarios.all()}): {@code n = 1} then returns exactly that order,
 * unchanged — today's behaviour.
 */
public final class ShardPlan {
    /** The owner's machine runs up to four Minecraft clients at once comfortably; more is not supported. */
    public static final int MAX_SHARDS = 4;
    /** Per-run overhead added to the run's own time limit for the balance estimate: a fresh world's creation
     * and teardown, mirroring {@code Scenario.budgetTicks()}'s fixed 400-tick (20 s) term. */
    public static final int OVERHEAD_SECONDS_PER_RUN = 20;

    private ShardPlan() {
    }

    /**
     * One scenario as the plan needs it.
     *
     * @param name             the scenario's own name, unique in the list
     * @param runs             how many runs it plays (1 for a CHECK, {@code Scenario.MEASURE_RUNS} for a MEASURE)
     * @param timeLimitSeconds how long one run runs, its own {@code seconds()}
     * @param compareWith      the Meteor scenario it is judged against, or null when it has none (Meteor's own
     *                         scenarios, and every CHECK)
     */
    public record Item(String name, int runs, int timeLimitSeconds, String compareWith) {
        public Item {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("a scenario name must not be blank");
            if (runs <= 0) throw new IllegalArgumentException(name + ": runs must be positive");
            if (timeLimitSeconds < 0) throw new IllegalArgumentException(name + ": timeLimitSeconds must not be negative");
        }

        /** {@link #runs} runs, each the time limit plus {@link #OVERHEAD_SECONDS_PER_RUN}. */
        long estimatedSeconds() {
            return (long) runs * (timeLimitSeconds + OVERHEAD_SECONDS_PER_RUN);
        }

        /** The compare group's key: the Meteor twin's name when judged against one, its own name otherwise. */
        private String groupKey() {
            return compareWith == null ? name : compareWith;
        }
    }

    /** One compare group: its items, in their original order, and their combined estimated duration. */
    private record Group(String key, List<Item> items, long seconds) {
    }

    /**
     * Shard {@code k} of {@code n} (1-based), the names {@link #plan} assigns it, in {@code items}' own order.
     *
     * @throws IllegalArgumentException {@code k} is not in {@code 1..n}
     */
    public static List<String> shard(List<Item> items, int k, int n) {
        Map<Integer, List<String>> plan = plan(items, n);
        List<String> names = plan.get(k);
        if (names == null) throw new IllegalArgumentException("shard " + k + " does not exist in a plan of " + n);
        return names;
    }

    /**
     * Every shard's own list of scenario names (1-based shard index), each in {@code items}' own order;
     * every item of {@code items} is in exactly one shard's list. Deterministic: the same {@code items} and
     * {@code n} always return the same plan.
     *
     * @throws IllegalArgumentException {@code n} is not in {@code 1..MAX_SHARDS}, {@code items} has a
     *                                   duplicate name, or is empty
     */
    public static Map<Integer, List<String>> plan(List<Item> items, int n) {
        Objects.requireNonNull(items, "items");
        if (n < 1 || n > MAX_SHARDS) throw new IllegalArgumentException("n must be 1.." + MAX_SHARDS + ": " + n);
        if (items.isEmpty()) throw new IllegalArgumentException("no scenario to shard");
        Set<String> seen = new LinkedHashSet<>();
        for (Item item : items) {
            if (!seen.add(item.name())) throw new IllegalArgumentException("duplicate scenario name: " + item.name());
        }

        // Group, keeping each group's items in items' own relative order (LinkedHashMap: first-seen key order).
        Map<String, List<Item>> byKey = new LinkedHashMap<>();
        for (Item item : items) byKey.computeIfAbsent(item.groupKey(), key -> new ArrayList<>()).add(item);
        List<Group> groups = new ArrayList<>();
        byKey.forEach((key, groupItems) -> {
            long seconds = groupItems.stream().mapToLong(Item::estimatedSeconds).sum();
            groups.add(new Group(key, groupItems, seconds));
        });
        // Original index of each group: its first item's position in items, for a deterministic tie-break.
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) firstIndex.putIfAbsent(items.get(i).groupKey(), i);
        // Longest first (LPT): tightens the balance; ties keep items' own order, never hash order.
        groups.sort((a, b) -> {
            int bySeconds = Long.compare(b.seconds(), a.seconds());
            return bySeconds != 0 ? bySeconds : Integer.compare(firstIndex.get(a.key()), firstIndex.get(b.key()));
        });

        // List scheduling: each group goes to the shard with the smallest running total so far; ties keep the
        // lowest shard index. This guarantees max shard total - min shard total <= the largest single group's.
        long[] totals = new long[n + 1];
        Map<Integer, List<Item>> assigned = new LinkedHashMap<>();
        for (int k = 1; k <= n; k++) assigned.put(k, new ArrayList<>());
        for (Group group : groups) {
            int chosen = 1;
            for (int k = 2; k <= n; k++) {
                if (totals[k] < totals[chosen]) chosen = k;
            }
            assigned.get(chosen).addAll(group.items());
            totals[chosen] += group.seconds();
        }

        // Each shard's own list back in items' original order (assignment above appended whole groups, which
        // can interleave two groups' relative order between each other).
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) order.put(items.get(i).name(), i);
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        assigned.forEach((k, list) -> {
            List<Item> sorted = new ArrayList<>(list);
            sorted.sort((a, b) -> Integer.compare(order.get(a.name()), order.get(b.name())));
            result.put(k, sorted.stream().map(Item::name).toList());
        });
        return result;
    }
}
