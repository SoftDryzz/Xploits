package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Verdict;
import com.xploits.bench.core.Recommendation.Judged;
import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task A5: turns {@code n} shard reports (each a {@code -Pbench.shard=k/n} client's own {@code
 * report-<version>.json}, parsed by a JSON library into maps, lists, strings, numbers and booleans, the way
 * {@link ReleaseGate} already reads a report) into ONE report, identical in shape and meaning to what an
 * unsharded run would have written: the same scenarios, in the same (unsharded) order, the same {@code
 * compare} summary and {@code recommendation} lines, worked out by the same code a single run uses ({@link
 * Recommendation#compareSummaryLine} and {@link Recommendation#lines}) rather than a second copy of that
 * arithmetic.
 *
 * <p>Every scenario's own fields (status, runs, median/min/max, regressions, its {@code compare} verdict
 * against its Meteor twin) are carried over exactly as its shard wrote them: {@link ShardPlan} never splits a
 * compare group, so whichever shard held a {@code capp-X}/{@code ca-X} pair judged it with both sides present,
 * the same as an unsharded run would have. Only the report-level rollups — the scenario order, the overall
 * {@code compare} line, and the per-level {@code recommendation} (which reads pairs from every shard, since a
 * level's pairs are rarely all in the same one) — are recomputed here.
 *
 * <p>Refuses (a null {@link Result#report()}, a message in {@link Result#refusal()}) unless every shard
 * agrees on what it ran (commit, addon and Meteor versions, profile, {@code only}, {@code fresh}, {@code ping}
 * and {@code verifySettle}), every {@code k} of the same {@code n} is present exactly once, every scenario of
 * the unsharded selection appears in exactly one shard, and every shard's own hygiene scan was clean. This
 * class never touches a file: what fed it, and what it hands back, are plain data. The {@code hygiene} field
 * of the merged report is deliberately left out here — the same way a live run only adds it after writing
 * the file and scanning it — so the caller scans the files it writes and adds that field itself.
 */
public final class ReportMerge {
    private static final Pattern SHARD_LABEL = Pattern.compile("(\\d+)/(\\d+)");
    /** Report fields every shard must agree on (besides the shard label itself): what they all ran. */
    private static final List<String> AGREED_FIELDS =
        List.of("schema", "addon", "meteor", "difficulty", "only", "profile", "commit", "fresh", "ping", "verify_settle");

    private ReportMerge() {
    }

    /** The merged report (as a plain map, ready for a JSON library to write), or why it was refused. */
    public record Result(Map<String, Object> report, String refusal) {
        static Result ok(Map<String, Object> report) {
            return new Result(report, null);
        }

        static Result refuse(String reason) {
            return new Result(null, reason);
        }

        public boolean ok() {
            return refusal == null;
        }
    }

    /**
     * @param shards every shard's own report, parsed into maps/lists/strings/numbers/booleans (any order:
     *               this sorts them by their own {@code shard} field)
     */
    public static Result merge(List<Map<String, ?>> shards) {
        Objects.requireNonNull(shards, "shards");
        if (shards.isEmpty()) return Result.refuse("bench: no shard report to merge");
        List<String> problems = new ArrayList<>();

        TreeMap<Integer, Map<String, ?>> byK = new TreeMap<>();
        Integer n = null;
        for (Map<String, ?> report : shards) {
            Matcher label = shardLabel(report);
            if (label == null) {
                problems.add("a report has no valid \"shard\": \"k/n\" field (not sharded, or not from this task)");
                continue;
            }
            int k = Integer.parseInt(label.group(1));
            int reportN = Integer.parseInt(label.group(2));
            if (n == null) n = reportN;
            else if (!n.equals(reportN)) problems.add("shard " + k + "/" + reportN + " does not agree on n with the others (" + n + ")");
            if (byK.containsKey(k)) problems.add("shard " + k + "/" + reportN + " is present more than once");
            else byK.put(k, report);
        }
        if (!problems.isEmpty()) return Result.refuse(String.join("; ", problems));
        for (int k = 1; k <= n; k++) {
            if (!byK.containsKey(k)) problems.add("shard " + k + "/" + n + " is missing");
        }
        if (byK.size() != n) problems.add("expected " + n + " shard(s), got " + byK.size());
        if (!problems.isEmpty()) return Result.refuse(String.join("; ", problems));

        Map<String, ?> first = byK.get(1);
        for (Map.Entry<Integer, Map<String, ?>> entry : byK.entrySet()) {
            for (String field : AGREED_FIELDS) {
                if (!Objects.equals(first.get(field), entry.getValue().get(field))) {
                    problems.add("shard " + entry.getKey() + "/" + n + "'s " + field + " (" + entry.getValue().get(field)
                        + ") does not match shard 1's (" + first.get(field) + ")");
                }
            }
            Object hygiene = entry.getValue().get("hygiene");
            if (!"clean".equals(hygiene)) {
                problems.add("shard " + entry.getKey() + "/" + n + "'s hygiene is not clean");
            }
        }
        List<String> canonicalOrder = stringList(first.get("canonical_order"));
        if (canonicalOrder.isEmpty()) problems.add("shard 1/" + n + " has no canonical_order to restore the unsharded order from");
        for (Map.Entry<Integer, Map<String, ?>> entry : byK.entrySet()) {
            if (!canonicalOrder.equals(stringList(entry.getValue().get("canonical_order")))) {
                problems.add("shard " + entry.getKey() + "/" + n + "'s canonical_order does not match shard 1's");
            }
            if (!Objects.equals(first.get("judged"), entry.getValue().get("judged"))) {
                problems.add("shard " + entry.getKey() + "/" + n + "'s judged pairs do not match shard 1's");
            }
        }
        if (!problems.isEmpty()) return Result.refuse(String.join("; ", problems));

        // Every scenario, from every shard, keyed by name: each must appear in exactly one shard, and the
        // set must match canonical_order's (the unsharded selection every shard was handed a slice of).
        Map<String, Map<String, ?>> scenarioOf = new LinkedHashMap<>();
        Map<String, Integer> shardOf = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, ?>> entry : byK.entrySet()) {
            for (Object element : listOf(entry.getValue().get("scenarios"))) {
                if (!(element instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, ?> scenario = (Map<String, ?>) raw;
                String name = String.valueOf(scenario.get("name"));
                if (scenarioOf.containsKey(name)) {
                    problems.add(name + " is in more than one shard (" + shardOf.get(name) + " and " + entry.getKey() + ")");
                    continue;
                }
                scenarioOf.put(name, scenario);
                shardOf.put(name, entry.getKey());
            }
        }
        Set<String> canonicalSet = new LinkedHashSet<>(canonicalOrder);
        for (String name : canonicalOrder) {
            if (!scenarioOf.containsKey(name)) problems.add(name + " is missing from every shard");
        }
        for (String name : scenarioOf.keySet()) {
            if (!canonicalSet.contains(name)) problems.add(name + " is not in the unsharded selection");
        }
        if (!problems.isEmpty()) return Result.refuse(String.join("; ", problems));

        List<Object> mergedScenarios = new ArrayList<>();
        List<Verdict> verdicts = new ArrayList<>();
        for (String name : canonicalOrder) {
            Map<String, ?> scenario = scenarioOf.get(name);
            mergedScenarios.add(scenario);
            verdictOf(scenario).ifPresent(verdicts::add);
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        for (String field : List.of("schema", "addon", "meteor", "difficulty", "only", "profile")) merged.put(field, first.get(field));
        merged.put("scenarios", mergedScenarios);
        if (!verdicts.isEmpty()) merged.put("compare", Recommendation.compareSummaryLine(verdicts));
        List<String> recommendations = recommendations(first, scenarioOf);
        if (!recommendations.isEmpty()) merged.put("recommendation", recommendations);
        return Result.ok(merged);
    }

    /** The recommendation lines over every judged pair ({@code judged}, from any shard: they all agree), the
     * same {@link Recommendation#lines} a single run's report calls. */
    private static List<String> recommendations(Map<String, ?> first, Map<String, Map<String, ?>> scenarioOf) {
        Object judgedRaw = first.get("judged");
        if (!(judgedRaw instanceof Map<?, ?> judged) || judged.isEmpty()) return List.of();
        List<Judged> pairs = new ArrayList<>();
        judged.forEach((name, levelName) -> {
            RiskLevel level = RiskLevel.valueOf(String.valueOf(levelName));
            Map<String, ?> scenario = scenarioOf.get(String.valueOf(name));
            Verdict verdict = scenario == null ? Verdict.INCOMPLETE : verdictOf(scenario).orElse(Verdict.INCOMPLETE);
            pairs.add(new Judged(level, verdict));
        });
        Profile profile = Profile.fromId(String.valueOf(first.get("profile")));
        return Recommendation.lines(pairs, profile.notMeasured());
    }

    private static java.util.Optional<Verdict> verdictOf(Map<String, ?> scenario) {
        if (!(scenario.get("compare") instanceof Map<?, ?> compare)) return java.util.Optional.empty();
        Object verdict = compare.get("verdict");
        return verdict == null ? java.util.Optional.empty() : java.util.Optional.of(Verdict.valueOf(String.valueOf(verdict)));
    }

    private static Matcher shardLabel(Map<String, ?> report) {
        Object shard = report.get("shard");
        if (!(shard instanceof String text)) return null;
        Matcher m = SHARD_LABEL.matcher(text.strip());
        return m.matches() ? m : null;
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object element : listOf(value)) out.add(String.valueOf(element));
        return out;
    }
}
