package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task A5: turning several {@code -Pbench.shard=k/n} reports into one, identical in shape and meaning to an
 * unsharded run's — the scenario order restored, the {@code compare} and {@code recommendation} lines worked
 * out the same way a single run's are — and refusing, with a clear reason, whenever the shards do not agree
 * on what they ran or do not add up to a complete, once-each selection.
 */
class ReportMergeTest {
    private static Map<String, Object> scenario(String name, String status, Map<String, Object> compare) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("kind", "MEASURE");
        s.put("status", status);
        if (compare != null) s.put("compare", compare);
        return s;
    }

    private static Map<String, Object> compare(String with, String verdict) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("with", with);
        c.put("verdict", verdict);
        return c;
    }

    /** A base shard report: two scenarios, no risk pair. Tests override fields as needed. */
    private static Map<String, Object> report(int k, int n, List<Map<String, Object>> scenarios,
                                                List<String> canonicalOrder, Map<String, String> judged) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema", 1.0);
        r.put("addon", "0.7.1");
        r.put("meteor", "0.7.0");
        r.put("difficulty", "normal");
        r.put("only", null);
        r.put("profile", "full");
        r.put("shard", k + "/" + n);
        r.put("commit", "abc123");
        r.put("tree_clean", true);
        r.put("fresh", true);
        r.put("ping", 100.0);
        r.put("verify_settle", false);
        r.put("hygiene", "clean");
        r.put("canonical_order", canonicalOrder);
        r.put("judged", judged);
        r.put("scenarios", scenarios);
        return r;
    }

    /** Two shards: shard 1 has ca-still/capp-still (ACCEPT), shard 2 has ca-circler/capp-circler (REJECT). */
    private static List<Map<String, ?>> twoShardsSplitByPair() {
        List<String> order = List.of("ca-still", "capp-still", "ca-circler", "capp-circler");
        Map<String, String> judged = new LinkedHashMap<>();
        judged.put("capp-still", "SAFE");
        judged.put("capp-circler", "SAFE");
        List<Map<String, Object>> shard1Scenarios = List.of(
            scenario("ca-still", "DONE", null),
            scenario("capp-still", "DONE", compare("ca-still", "ACCEPT")));
        List<Map<String, Object>> shard2Scenarios = List.of(
            scenario("ca-circler", "DONE", null),
            scenario("capp-circler", "DONE", compare("ca-circler", "REJECT")));
        List<Map<String, ?>> shards = new ArrayList<>();
        shards.add(report(1, 2, shard1Scenarios, order, judged));
        shards.add(report(2, 2, shard2Scenarios, order, judged));
        return shards;
    }

    @Test
    void scenariosComeBackInTheUnshardedOrder() {
        ReportMerge.Result result = ReportMerge.merge(twoShardsSplitByPair());
        assertTrue(result.ok(), result.refusal());
        @SuppressWarnings("unchecked")
        List<Map<String, ?>> scenarios = (List<Map<String, ?>>) (List<?>) result.report().get("scenarios");
        assertEquals(List.of("ca-still", "capp-still", "ca-circler", "capp-circler"),
            scenarios.stream().map(s -> s.get("name")).toList());
    }

    @Test
    void mergingWorksWhateverOrderTheShardsAreHandedIn() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        List<Map<String, ?>> reversed = List.of(shards.get(1), shards.get(0));
        ReportMerge.Result result = ReportMerge.merge(reversed);
        assertTrue(result.ok(), result.refusal());
        @SuppressWarnings("unchecked")
        List<Map<String, ?>> scenarios = (List<Map<String, ?>>) (List<?>) result.report().get("scenarios");
        assertEquals(List.of("ca-still", "capp-still", "ca-circler", "capp-circler"),
            scenarios.stream().map(s -> s.get("name")).toList());
    }

    @Test
    void theCompareSummaryCountsEveryShardsVerdicts() {
        ReportMerge.Result result = ReportMerge.merge(twoShardsSplitByPair());
        assertTrue(result.ok(), result.refusal());
        assertEquals("capp: 1 ACCEPT / 1 REJECT / 0 INCOMPLETE / 0 NOT_APPLICABLE", result.report().get("compare"));
    }

    @Test
    void theRecommendationReadsPairsAcrossShards() {
        // Both capp-still (shard 1) and capp-circler (shard 2) are judged Safe: one REJECT anywhere is a NO.
        ReportMerge.Result result = ReportMerge.merge(twoShardsSplitByPair());
        assertTrue(result.ok(), result.refusal());
        assertEquals(List.of("capp Safe: NO (1 of 2 applicable)"), result.report().get("recommendation"));
    }

    @Test
    void aLevelWhosePairsAreAllAcceptIsYes() {
        List<String> order = List.of("ca-still", "capp-still", "ca-circler", "capp-circler");
        Map<String, String> judged = new LinkedHashMap<>();
        judged.put("capp-still", "SAFE");
        judged.put("capp-circler", "SAFE");
        List<Map<String, Object>> shard1 = List.of(scenario("ca-still", "DONE", null),
            scenario("capp-still", "DONE", compare("ca-still", "ACCEPT")));
        List<Map<String, Object>> shard2 = List.of(scenario("ca-circler", "DONE", null),
            scenario("capp-circler", "DONE", compare("ca-circler", "ACCEPT")));
        ReportMerge.Result result = ReportMerge.merge(List.of(report(1, 2, shard1, order, judged),
            report(2, 2, shard2, order, judged)));
        assertTrue(result.ok(), result.refusal());
        assertEquals(List.of("capp Safe: YES (2 of 2 applicable)"), result.report().get("recommendation"));
    }

    @Test
    void aJudgedPairThatDidNotRunInAnyShardIsIncomplete() {
        // capp-circler is judged, but no shard carries it: the everyday run's own "not selected" shape.
        List<String> order = List.of("ca-still", "capp-still");
        Map<String, String> judged = new LinkedHashMap<>();
        judged.put("capp-still", "SAFE");
        judged.put("capp-circler", "SAFE");
        List<Map<String, Object>> shard1 = List.of(scenario("ca-still", "DONE", null),
            scenario("capp-still", "DONE", compare("ca-still", "ACCEPT")));
        Map<String, Object> onlyShard = report(1, 1, shard1, order, judged);
        ReportMerge.Result result = ReportMerge.merge(List.of(onlyShard));
        assertTrue(result.ok(), result.refusal());
        // capp-still ACCEPT plus capp-circler INCOMPLETE (it is judged but ran in no shard): one REJECT-grade
        // pair among two applicable is a NO, exactly as a partial single run's would be.
        assertEquals(List.of("capp Safe: NO (1 of 2 applicable)"), result.report().get("recommendation"));
    }

    @Test
    void aSkippedScenarioIsIncompleteNotMissing() {
        // Present (so the completeness check is satisfied) but SKIPPED: no "compare" field, same as a real
        // run's Entry.compare() returning empty for a skipped scenario — counts as INCOMPLETE, not missing.
        List<String> order = List.of("ca-still", "capp-still");
        Map<String, String> judged = Map.of("capp-still", "SAFE");
        List<Map<String, Object>> shard = List.of(scenario("ca-still", "DONE", null),
            scenario("capp-still", "SKIPPED", null));
        Map<String, Object> report = report(1, 1, shard, order, judged);
        ReportMerge.Result result = ReportMerge.merge(List.of(report));
        assertTrue(result.ok(), result.refusal());
        assertEquals(List.of("capp Safe: NO (0 of 1 applicable)"), result.report().get("recommendation"));
    }

    @Test
    void noVerdictAtAllOmitsTheCompareLine() {
        List<String> order = List.of("panel");
        Map<String, Object> shard = report(1, 1, List.of(scenario("panel", "PASS", null)), order, Map.of());
        ReportMerge.Result result = ReportMerge.merge(List.of(shard));
        assertTrue(result.ok(), result.refusal());
        assertNull(result.report().get("compare"));
        assertNull(result.report().get("recommendation"));
    }

    @Test
    void theHygieneFieldIsNeverInTheMergedReport() {
        // The caller scans the files it writes and adds hygiene itself, the same as a live run's own two-pass scan.
        ReportMerge.Result result = ReportMerge.merge(twoShardsSplitByPair());
        assertTrue(result.ok(), result.refusal());
        assertFalse(result.report().containsKey("hygiene"));
    }

    // --- Refusals ------------------------------------------------------------------------------------

    @Test
    void noShardsIsRefused() {
        assertFalse(ReportMerge.merge(List.of()).ok());
    }

    @Test
    void aMissingShardIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0)));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("2/2"), result.refusal());
    }

    @Test
    void aRepeatedShardIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), shards.get(0)));
        assertFalse(result.ok());
    }

    @Test
    void aMismatchedCommitIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(1));
        tampered.put("commit", "def456");
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), tampered));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("commit"), result.refusal());
    }

    @Test
    void aMismatchedFlagIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(1));
        tampered.put("fresh", false);
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), tampered));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("fresh"), result.refusal());
    }

    @Test
    void aDirtyShardHygieneIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(1));
        tampered.put("hygiene", List.of("report.json line 3"));
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), tampered));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("hygiene"), result.refusal());
    }

    @Test
    void aScenarioPresentInTwoShardsIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shard1Scenarios = new ArrayList<>((List<Map<String, Object>>) (List<?>) shards.get(0).get("scenarios"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shard2Scenarios = new ArrayList<>((List<Map<String, Object>>) (List<?>) shards.get(1).get("scenarios"));
        shard2Scenarios.add(shard1Scenarios.get(0));
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(1));
        tampered.put("scenarios", shard2Scenarios);
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), tampered));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("ca-still"), result.refusal());
    }

    @Test
    void aScenarioMissingFromEveryShardIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shard1Scenarios = new ArrayList<>((List<Map<String, Object>>) (List<?>) shards.get(0).get("scenarios"));
        shard1Scenarios.remove(0); // drop ca-still, still named in canonical_order
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(0));
        tampered.put("scenarios", shard1Scenarios);
        ReportMerge.Result result = ReportMerge.merge(List.of(tampered, shards.get(1)));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("ca-still"), result.refusal());
    }

    @Test
    void aScenarioOutsideTheCanonicalOrderIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shard1Scenarios = new ArrayList<>((List<Map<String, Object>>) (List<?>) shards.get(0).get("scenarios"));
        shard1Scenarios.add(scenario("mystery-scenario", "DONE", null));
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(0));
        tampered.put("scenarios", shard1Scenarios);
        ReportMerge.Result result = ReportMerge.merge(List.of(tampered, shards.get(1)));
        assertFalse(result.ok());
        assertTrue(result.refusal().contains("mystery-scenario"), result.refusal());
    }

    @Test
    void aMismatchedCanonicalOrderIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        Map<String, Object> tampered = new LinkedHashMap<>(shards.get(1));
        tampered.put("canonical_order", List.of("ca-still", "capp-still", "ca-circler", "capp-circler", "extra"));
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), tampered));
        assertFalse(result.ok());
    }

    @Test
    void aMismatchedNIsRefused() {
        List<Map<String, ?>> shards = twoShardsSplitByPair();
        Map<String, Object> tampered = new HashMap<>(shards.get(1));
        tampered.put("shard", "2/3");
        ReportMerge.Result result = ReportMerge.merge(List.of(shards.get(0), tampered));
        assertFalse(result.ok());
    }

    @Test
    void aReportWithoutAShardLabelIsRefused() {
        Map<String, Object> noLabel = new HashMap<>(twoShardsSplitByPair().get(0));
        noLabel.remove("shard");
        ReportMerge.Result result = ReportMerge.merge(List.of(noLabel));
        assertFalse(result.ok());
    }
}
