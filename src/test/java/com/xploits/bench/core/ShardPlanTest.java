package com.xploits.bench.core;

import com.xploits.bench.core.ShardPlan.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task A5: splitting the bench's scenarios across up to {@value ShardPlan#MAX_SHARDS} clients without ever
 * separating a compare group ({@code ca-X} from every scenario judged against it), and without changing what
 * {@code n = 1} plays today.
 */
class ShardPlanTest {
    private static Item check(String name) {
        return new Item(name, 1, 10, null);
    }

    private static Item meteor(String name) {
        return new Item(name, 3, 30, null);
    }

    private static Item capp(String name, String with) {
        return new Item(name, 3, 30, with);
    }

    @Test
    void everyItemIsInExactlyOneShard() {
        List<Item> items = List.of(check("panel"), meteor("ca-still"), capp("capp-still", "ca-still"),
            capp("capp-balanced-still", "ca-still"), meteor("ca-circler"), capp("capp-circler", "ca-circler"),
            check("recorder-lost"));
        Map<Integer, List<String>> plan = ShardPlan.plan(items, 3);
        List<String> all = new ArrayList<>();
        plan.values().forEach(all::addAll);
        assertEquals(items.stream().map(Item::name).toList().size(), all.size());
        assertEquals(new LinkedHashSet<>(all).size(), all.size(), "a name appeared in more than one shard");
        Set<String> expected = new LinkedHashSet<>(items.stream().map(Item::name).toList());
        assertEquals(expected, new LinkedHashSet<>(all));
    }

    @Test
    void aCompareGroupIsNeverSplit() {
        // ca-still with its Safe, Balanced and Aggressive twins: wherever ca-still lands, so do all three.
        List<Item> items = List.of(meteor("ca-still"), capp("capp-still", "ca-still"),
            capp("capp-balanced-still", "ca-still"), capp("capp-aggressive-still", "ca-still"),
            meteor("ca-circler"), capp("capp-circler", "ca-circler"), meteor("ca-defender"),
            capp("capp-defender", "ca-defender"), check("panel"), check("recorder-lost"), check("autopvp-engages"));
        for (int n = 1; n <= ShardPlan.MAX_SHARDS; n++) {
            Map<Integer, List<String>> plan = ShardPlan.plan(items, n);
            assertSameShard(plan, "ca-still", "capp-still", "capp-balanced-still", "capp-aggressive-still");
            assertSameShard(plan, "ca-circler", "capp-circler");
            assertSameShard(plan, "ca-defender", "capp-defender");
        }
    }

    private static void assertSameShard(Map<Integer, List<String>> plan, String... names) {
        Integer shard = null;
        for (String name : names) {
            for (Map.Entry<Integer, List<String>> entry : plan.entrySet()) {
                if (entry.getValue().contains(name)) {
                    if (shard == null) shard = entry.getKey();
                    else assertEquals(shard, entry.getKey(), name + " split from its compare group");
                }
            }
        }
    }

    @Test
    void aGroupsOwnOrderIsKeptInsideItsShard() {
        List<Item> items = List.of(meteor("ca-still"), capp("capp-still", "ca-still"),
            capp("capp-balanced-still", "ca-still"), capp("capp-aggressive-still", "ca-still"));
        List<String> shard = ShardPlan.shard(items, 1, 1);
        assertEquals(List.of("ca-still", "capp-still", "capp-balanced-still", "capp-aggressive-still"), shard);
    }

    @Test
    void thePlanIsTheSameEveryTime() {
        List<Item> items = realScenarios();
        Map<Integer, List<String>> first = ShardPlan.plan(items, 4);
        Map<Integer, List<String>> second = ShardPlan.plan(new ArrayList<>(items), 4);
        assertEquals(first, second);
    }

    @Test
    void nEqualsOneIsIdenticalToToday() {
        List<Item> items = realScenarios();
        Map<Integer, List<String>> plan = ShardPlan.plan(items, 1);
        assertEquals(1, plan.size());
        assertEquals(items.stream().map(Item::name).toList(), plan.get(1));
    }

    @Test
    void aSaneBalanceOnTheRealScenarioList() {
        List<Item> items = realScenarios();
        // The proven property of "always add to the least loaded shard" (see ShardPlan's own javadoc): once
        // every group is assigned, the busiest shard's total minus the least busy shard's is at most the
        // duration of the single biggest compare group. Checked here against the bench's real 84 scenarios
        // rather than a made-up list, so the bound is the one the owner's actual runs get.
        long biggestGroup = biggestGroupSeconds(items);
        for (int n = 1; n <= ShardPlan.MAX_SHARDS; n++) {
            Map<Integer, List<String>> plan = ShardPlan.plan(items, n);
            List<Long> totals = plan.values().stream().map(names -> secondsOf(items, names)).toList();
            long max = totals.stream().mapToLong(Long::longValue).max().orElseThrow();
            long min = totals.stream().mapToLong(Long::longValue).min().orElseThrow();
            assertTrue(max - min <= biggestGroup,
                "n=" + n + ": spread " + (max - min) + " s > biggest group " + biggestGroup + " s");
        }
        // n = 4 is worth using at all: the busiest shard must be well under the unsharded total.
        long total = items.stream().mapToLong(Item::estimatedSeconds).sum();
        long busiest = ShardPlan.plan(items, 4).values().stream().map(names -> secondsOf(items, names))
            .mapToLong(Long::longValue).max().orElseThrow();
        assertTrue(busiest <= total / 2, "n=4's busiest shard (" + busiest + " s) should be well under the total (" + total + " s)");
    }

    /**
     * Proves the "largest first" (LPT) ordering the javadoc claims, not just the weaker
     * max-min &le; biggest-group bound {@link #aSaneBalanceOnTheRealScenarioList} checks (which holds for
     * any group order, so a mutation dropping the sort would not fail it). Groups B, C and D (20 s each)
     * come before the 60 s group A in the input; LPT visits A first regardless of input order, landing it
     * alone on shard 1 while B, C and D share shard 2 (60/60, perfectly balanced). Visiting groups in the
     * given order instead would put B then A on shard 1 (80 s) and leave only C and D on shard 2 (40 s) — a
     * different, and worse, split. The two orders produce different shard *contents*, not just different
     * totals, so this pins down the sort itself.
     */
    @Test
    void groupsAreAssignedLargestFirst() {
        List<Item> items = List.of(
            new Item("B", 1, 0, null),
            new Item("C", 1, 0, null),
            new Item("A", 3, 0, null),
            new Item("D", 1, 0, null));
        Map<Integer, List<String>> plan = ShardPlan.plan(items, 2);
        assertEquals(List.of("A"), plan.get(1));
        assertEquals(List.of("B", "C", "D"), plan.get(2));
    }

    /**
     * More shards than compare groups (e.g. a small {@code -Pbench.only} selection with {@code -Pbench.shards
     * = 4}): {@code plan} still returns one list per shard 1..n, the extra ones empty, and no scenario is
     * lost. The runner does not skip an empty shard — it still launches a full client for it, which plays
     * nothing and writes a valid, hygiene-clean, zero-scenario report (wasteful, but harmless: {@code
     * ReportMerge} does not require every shard to contribute at least one scenario, only that every
     * scenario of the selection appears in exactly one — see {@code ReportMergeTest}).
     */
    @Test
    void moreShardsThanGroupsLeavesTheExtraOnesEmpty() {
        // ca-still and capp-still are one compare group (capp-still's compareWith is ca-still): only one
        // group for 4 shards, so three of them get nothing.
        List<Item> items = List.of(meteor("ca-still"), capp("capp-still", "ca-still"));
        Map<Integer, List<String>> plan = ShardPlan.plan(items, 4);
        assertEquals(4, plan.size());
        List<String> nonEmpty = plan.values().stream().filter(names -> !names.isEmpty())
            .flatMap(List::stream).toList();
        assertEquals(Set.of("ca-still", "capp-still"), Set.copyOf(nonEmpty));
        long emptyShards = plan.values().stream().filter(List::isEmpty).count();
        assertEquals(3, emptyShards);
    }

    private static long secondsOf(List<Item> items, List<String> names) {
        Set<String> wanted = Set.copyOf(names);
        return items.stream().filter(i -> wanted.contains(i.name())).mapToLong(Item::estimatedSeconds).sum();
    }

    private static long biggestGroupSeconds(List<Item> items) {
        Map<String, Long> byKey = new java.util.LinkedHashMap<>();
        for (Item item : items) {
            String key = item.compareWith() == null ? item.name() : item.compareWith();
            byKey.merge(key, item.estimatedSeconds(), Long::sum);
        }
        return byKey.values().stream().mapToLong(Long::longValue).max().orElseThrow();
    }

    @Test
    void nOutsideOneToMaxIsRejected() {
        List<Item> items = List.of(check("panel"));
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.plan(items, 0));
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.plan(items, ShardPlan.MAX_SHARDS + 1));
    }

    @Test
    void aDuplicateNameIsRejected() {
        List<Item> items = List.of(check("panel"), check("panel"));
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.plan(items, 1));
    }

    @Test
    void noItemsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.plan(List.of(), 1));
    }

    @Test
    void shardOfANonExistentIndexIsRejected() {
        List<Item> items = List.of(check("panel"));
        assertThrows(IllegalArgumentException.class, () -> ShardPlan.shard(items, 2, 1));
    }

    @Test
    void anItemNeedsAName() {
        assertThrows(NullPointerException.class, () -> new Item(null, 1, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new Item(" ", 1, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new Item("x", 0, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new Item("x", 1, -1, null));
    }

    /**
     * A stand-in for {@code Scenarios.all()} (task A5): the same 72 real scenarios {@code ScenarioSelectionTest}
     * mirrors, this time with each one's own runs and time limit ({@code Scenario.MEASURE_RUNS} = 3, one run for
     * a CHECK; {@code CrystalAuraMeasure}/{@code FightMeasure}/{@code CityMeasure} all run 30 s, the CHECKs their
     * own seconds()), so the balance test above runs on the real shape of the bench, not a made-up list.
     */
    private static List<Item> realScenarios() {
        List<Item> all = new ArrayList<>();
        // The 9 CHECKs, in Scenarios.all()'s order, each with its own seconds() (RecorderPopEnd 25, RecorderLost 8,
        // RecorderOpponent 5, AutoPvpEngages 11, AutoPvpEngagesCapp 21, CappBudgetOffParity 30, ProfileDefensive 11,
        // AutoPvpAntiResources 12, Panel 6).
        all.add(new Item("recorder-pop-end", 1, 25, null));
        all.add(new Item("recorder-lost", 1, 8, null));
        all.add(new Item("recorder-opponent", 1, 5, null));
        all.add(new Item("autopvp-engages", 1, 11, null));
        all.add(new Item("autopvp-engages-capp", 1, 21, null));
        all.add(new Item("capp-budget-off-parity", 1, 30, null));
        all.add(new Item("profile-defensive", 1, 11, null));
        all.add(new Item("autopvp-anti-resources", 1, 12, null));
        all.add(new Item("panel", 1, 6, null));
        all.add(new Item("exposure-cover-probe", 1, 12, null));
        all.add(new Item("baritone-net", 1, 5, null));
        all.add(new Item("restock-no-litematica", 1, 5, null));
        all.add(new Item("restock-trip", 1, 30, null));
        for (String s : List.of("still", "circler", "defender", "still-regen", "circler-regen")) {
            all.add(meteor("ca-" + s));
        }
        // capp-defender (Safe only: OTHER_LEVELS never runs it at Balanced/Aggressive) sits between capp-circler
        // and capp-still-regen, exactly where Scenarios.all() puts it.
        for (String s : List.of("still", "circler", "defender", "still-regen", "circler-regen")) {
            all.add(capp("capp-" + s, "ca-" + s));
        }
        capps(all, List.of("still", "circler", "still-regen", "circler-regen"));
        all.add(meteor("defense-attacker"));
        List<String> fights = List.of("above", "below", "approach", "strafe");
        for (String f : fights) all.add(meteor("ca-" + f + "-regen"));
        for (String f : fights) all.add(capp("capp-" + f + "-regen", "ca-" + f + "-regen"));
        for (String level : List.of("balanced", "aggressive")) {
            for (String f : fights) all.add(capp("capp-" + level + "-" + f + "-regen", "ca-" + f + "-regen"));
        }
        List<String> selfFights = List.of("self-circle", "self-strafe");
        for (String f : selfFights) all.add(meteor("ca-" + f + "-regen"));
        for (String f : selfFights) all.add(capp("capp-" + f + "-regen", "ca-" + f + "-regen"));
        for (String level : List.of("balanced", "aggressive")) {
            for (String f : selfFights) all.add(capp("capp-" + level + "-" + f + "-regen", "ca-" + f + "-regen"));
        }
        all.add(meteor("ca-cover"));
        all.add(capp("capp-cover", "ca-cover"));
        for (String level : List.of("balanced", "aggressive")) all.add(capp("capp-" + level + "-cover", "ca-cover"));
        all.add(meteor("ca-roof-jump-regen"));
        all.add(capp("capp-roof-jump-regen", "ca-roof-jump-regen"));
        for (String level : List.of("balanced", "aggressive")) {
            all.add(capp("capp-" + level + "-roof-jump-regen", "ca-roof-jump-regen"));
        }
        realFights(all, List.of("exchange", "hole-standoff"));
        all.add(meteor("ca-city"));
        all.add(capp("capp-city", "ca-city"));
        for (String level : List.of("balanced", "aggressive")) all.add(capp("capp-" + level + "-city", "ca-city"));
        realFights(all, List.of("near-death", "near-death-totem"));
        return List.copyOf(all);
    }

    private static void capps(List<Item> all, List<String> names) {
        for (String level : List.of("balanced", "aggressive")) {
            for (String s : names) all.add(capp("capp-" + level + "-" + s, "ca-" + s));
        }
    }

    private static void realFights(List<Item> all, List<String> fights) {
        for (String f : fights) all.add(meteor("ca-" + f));
        for (String f : fights) all.add(capp("capp-" + f, "ca-" + f));
        for (String level : List.of("balanced", "aggressive")) {
            for (String f : fights) all.add(capp("capp-" + level + "-" + f, "ca-" + f));
        }
    }

    @Test
    void theRealListMirrorHas84Scenarios() {
        assertEquals(84, realScenarios().size());
        assertEquals(84, realScenarios().stream().map(Item::name).distinct().count());
    }

    @Test
    void namesAreCaseSensitiveAndUnaffectedByLocale() {
        // Sanity: the mirror above never relies on default locale for its "balanced"/"aggressive" prefixes.
        assertEquals("capp-balanced-still", realScenarios().stream().map(Item::name)
            .filter(n -> n.equals("capp-balanced-still".toLowerCase(Locale.ROOT))).findFirst().orElseThrow());
    }
}
