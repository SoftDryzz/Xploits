package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Kind;
import com.xploits.bench.core.Acceptance.Outcome;
import com.xploits.bench.core.Acceptance.Result;
import com.xploits.bench.core.Acceptance.Rule;
import com.xploits.bench.core.Acceptance.Side;
import com.xploits.bench.core.Acceptance.Verdict;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crystal-aura++ acceptance rules (crystal-aura++ spec §4, P6, Q5, Q6): every rule at its boundary,
 * in both of its branches where it has two, per-run extremes against medians, and the INCOMPLETE and
 * REJECT cases around the runs. Every number that sits on a boundary is exact in binary.
 */
class AcceptanceTest {
    private static final String CAPP = "capp-still";
    private static final String CA = "ca-still";

    /** One run's numbers; {@code firstPop} null for a run in which the sparring never popped. */
    private static Map<String, Double> run(double damage, double pops, Double firstPop, double selfDamage,
                                           double selfPops, double minHealth) {
        Map<String, Double> m = new HashMap<>();
        m.put(Acceptance.DAMAGE_DEALT, damage);
        m.put(Acceptance.SPARRING_POPS, pops);
        if (firstPop != null) m.put(Acceptance.FIRST_POP_S, firstPop);
        m.put(Acceptance.NO_POP_RUNS, pops == 0 ? 1.0 : 0.0);
        m.put(Acceptance.SELF_DAMAGE, selfDamage);
        m.put(Acceptance.SELF_POPS, selfPops);
        m.put(Acceptance.MIN_HEALTH, minHealth);
        return m;
    }

    /** Meteor's crystal-aura as the baseline measured it on the still target: loud on itself. */
    private static Map<String, Double> meteorRun() {
        return run(20, 2, 1.0, 16, 0, 4);
    }

    /** A crystal-aura++ run that passes every rule against {@link #meteorRun}. */
    private static Map<String, Double> goodRun() {
        return run(20, 2, 1.0, 8, 0, 9);
    }

    private static List<Map<String, Double>> three(Map<String, Double> run) {
        return List.of(run, new HashMap<>(run), new HashMap<>(run));
    }

    private static Side capp(List<Map<String, Double>> runs) {
        return new Side(CAPP, "DONE", runs, false);
    }

    private static Side meteor(List<Map<String, Double>> runs) {
        return new Side(CA, "DONE", runs, false);
    }

    /** Three copies of {@link #goodRun} with one metric replaced in each run by the values given. */
    private static List<Map<String, Double>> good(String metric, double... values) {
        return with(goodRun(), metric, values);
    }

    private static List<Map<String, Double>> meteorWith(String metric, double... values) {
        return with(meteorRun(), metric, values);
    }

    private static List<Map<String, Double>> with(Map<String, Double> base, String metric, double... values) {
        List<Map<String, Double>> runs = new ArrayList<>();
        for (double v : values) {
            Map<String, Double> m = new HashMap<>(base);
            m.put(metric, v);
            runs.add(m);
        }
        return runs;
    }

    private static Outcome judge(List<Map<String, Double>> capp, List<Map<String, Double>> meteor) {
        return Acceptance.judge(capp(capp), meteor(meteor));
    }

    private static Rule rule(Outcome outcome, String id) {
        return outcome.rules().stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("no rule " + id + " in " + outcome));
    }

    private static Result result(List<Map<String, Double>> capp, List<Map<String, Double>> meteor, String id) {
        return rule(judge(capp, meteor), id).result();
    }

    // --- The whole verdict --------------------------------------------------------------------------

    @Test
    void aRunThatPassesEveryRuleIsAccepted() {
        Outcome outcome = judge(three(goodRun()), three(meteorRun()));
        assertEquals(Verdict.ACCEPT, outcome.verdict());
        assertEquals(List.of("S1", "S2", "S3", "O1", "O2", "O3", "O4"), outcome.rules().stream().map(Rule::id).toList());
        assertEquals(List.of(Kind.SAFETY, Kind.SAFETY, Kind.SAFETY, Kind.OFFENSE, Kind.OFFENSE, Kind.OFFENSE, Kind.OFFENSE),
            outcome.rules().stream().map(Rule::kind).toList());
        assertTrue(outcome.rules().stream().allMatch(r -> r.result() == Result.PASS), outcome.toString());
        assertNull(outcome.reason());
    }

    @Test
    void oneFailedRuleIsAReject() {
        // Only O2: ++ pops the sparring twice less than Meteor.
        Outcome outcome = judge(good(Acceptance.SPARRING_POPS, 0.5, 0.5, 0.5), three(meteorRun()));
        assertEquals(Verdict.REJECT, outcome.verdict());
        assertEquals(List.of("O2"), outcome.rules().stream().filter(r -> r.result() == Result.FAIL).map(Rule::id).toList());
        assertEquals("failed: O2", outcome.reason());
    }

    @Test
    void theReasonNamesEveryFailedRuleInOrder() {
        // The same numbers as Meteor: self damage not improved (S3), and nothing else.
        Outcome same = judge(three(meteorRun()), three(meteorRun()));
        assertEquals(Verdict.REJECT, same.verdict());
        assertEquals("failed: S3", same.reason());

        List<Map<String, Double>> worse = three(run(10, 0.5, 1.0, 16, 1, 1));
        Outcome outcome = judge(worse, three(meteorRun()));
        assertEquals("failed: S1, S2, S3, O1, O2", outcome.reason());
    }

    @Test
    void aNotApplicableRuleDoesNotStopAnAccept() {
        // Meteor never hurt itself (S3 does not apply) and ++ never popped (O4 does not apply, O3 carries it).
        List<Map<String, Double>> quietMeteor = three(run(0, 0, null, 0, 0, 20));
        List<Map<String, Double>> quietCapp = three(run(0, 0, null, 0, 0, 20));
        Outcome outcome = judge(quietCapp, quietMeteor);
        assertEquals(Verdict.ACCEPT, outcome.verdict());
        assertEquals(Result.NOT_APPLICABLE, rule(outcome, "S3").result());
        assertEquals(Result.NOT_APPLICABLE, rule(outcome, "O4").result());
    }

    // --- S1: the worst run's self pops, never worse -------------------------------------------------

    @Test
    void s1ComparesTheWorstRunsNotTheMedians() {
        // Medians are both 0; ++'s worst run popped once and Meteor's never did.
        assertEquals(Result.FAIL, result(good(Acceptance.SELF_POPS, 0, 1, 0), meteorWith(Acceptance.SELF_POPS, 0, 0, 0), "S1"));
        // Meteor's worst run popped once too: equal is not worse.
        assertEquals(Result.PASS, result(good(Acceptance.SELF_POPS, 0, 1, 0), meteorWith(Acceptance.SELF_POPS, 1, 0, 0), "S1"));
        assertEquals(Result.PASS, result(good(Acceptance.SELF_POPS, 0, 0, 0), meteorWith(Acceptance.SELF_POPS, 0, 0, 1), "S1"));
        // No noise floor on a safety rule: one pop more is worse.
        assertEquals(Result.FAIL, result(good(Acceptance.SELF_POPS, 2, 0, 0), meteorWith(Acceptance.SELF_POPS, 1, 1, 1), "S1"));
    }

    // --- S2: every run above F - 0.5, and the median min health within 1 of Meteor's ----------------

    @Test
    void s2WantsEveryRunAboveTheFloorLessHalf() {
        assertEquals(1.5, Acceptance.LOWEST_MIN_HEALTH);
        assertEquals(Result.PASS, result(good(Acceptance.MIN_HEALTH, 9, 1.5, 9), three(meteorRun()), "S2"));
        // One run below, with the median well above Meteor's: still a failure.
        assertEquals(Result.FAIL, result(good(Acceptance.MIN_HEALTH, 9, 1.25, 9), three(meteorRun()), "S2"));
    }

    @Test
    void s2AllowsTheMedianOneBelowMeteorsAndNoMore() {
        // Meteor's median 4: the limit is 3.
        assertEquals(Result.PASS, result(good(Acceptance.MIN_HEALTH, 3, 3, 3), meteorWith(Acceptance.MIN_HEALTH, 4, 4, 4), "S2"));
        assertEquals(Result.FAIL, result(good(Acceptance.MIN_HEALTH, 2.75, 2.75, 2.75), meteorWith(Acceptance.MIN_HEALTH, 4, 4, 4), "S2"));
    }

    @Test
    void s2UsesTheMedianForTheComparisonWithMeteor() {
        // ++ runs 2, 3, 20: median 3 meets the limit although its lowest run is under it; Meteor's median is
        // 4 although its highest run is 20.
        assertEquals(Result.PASS, result(good(Acceptance.MIN_HEALTH, 2, 3, 20), meteorWith(Acceptance.MIN_HEALTH, 4, 20, 1), "S2"));
        assertEquals(Result.FAIL, result(good(Acceptance.MIN_HEALTH, 2, 2.5, 20), meteorWith(Acceptance.MIN_HEALTH, 4, 20, 1), "S2"));
    }

    // --- S3: when Meteor hurts itself, ++ must improve ----------------------------------------------

    @Test
    void s3DoesNotApplyWhenMeteorsSelfDamageIsBelowTwo() {
        List<Map<String, Double>> meteor = meteorWith(Acceptance.SELF_DAMAGE, 1.75, 1.75, 1.75);
        assertEquals(Result.NOT_APPLICABLE, result(good(Acceptance.SELF_DAMAGE, 1.75, 1.75, 1.75), meteor, "S3"));
        // From 2 it applies: 2 - max(1, 0.3) = 1.
        meteor = meteorWith(Acceptance.SELF_DAMAGE, 2, 2, 2);
        List<Map<String, Double>> sameHealth = with(goodRun(), Acceptance.MIN_HEALTH, 4, 4, 4);
        assertEquals(Result.PASS, result(with(sameHealth.getFirst(), Acceptance.SELF_DAMAGE, 1, 1, 1), meteor, "S3"));
        assertEquals(Result.FAIL, result(with(sameHealth.getFirst(), Acceptance.SELF_DAMAGE, 1.25, 1.25, 1.25), meteor, "S3"));
    }

    @Test
    void s3WantsFifteenPercentLessSelfDamageWhenThatIsMoreThanOne() {
        // Meteor 20: 15 % is 3, the limit 17. The min health is Meteor's, so only the self damage can pass it.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.SELF_DAMAGE, 20, 20, 20);
        Map<String, Double> base = new HashMap<>(goodRun());
        base.put(Acceptance.MIN_HEALTH, 4.0);
        assertEquals(Result.PASS, result(with(base, Acceptance.SELF_DAMAGE, 17, 17, 17), meteor, "S3"));
        assertEquals(Result.FAIL, result(with(base, Acceptance.SELF_DAMAGE, 17.5, 17.5, 17.5), meteor, "S3"));
    }

    @Test
    void s3PassesOnAMinHealthOneAboveMeteorsInstead() {
        // No improvement in self damage, but the median min health is Meteor's 4 plus 1.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.SELF_DAMAGE, 20, 20, 20);
        Map<String, Double> base = new HashMap<>(goodRun());
        base.put(Acceptance.SELF_DAMAGE, 20.0);
        assertEquals(Result.PASS, result(with(base, Acceptance.MIN_HEALTH, 5, 5, 5), meteor, "S3"));
        assertEquals(Result.FAIL, result(with(base, Acceptance.MIN_HEALTH, 4.75, 4.75, 4.75), meteor, "S3"));
    }

    @Test
    void s3UsesMedians() {
        // ++ self damage 0, 20, 20: its median 20 is no improvement although one run dealt nothing.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.SELF_DAMAGE, 20, 0, 20);
        Map<String, Double> base = new HashMap<>(goodRun());
        base.put(Acceptance.MIN_HEALTH, 4.0);
        assertEquals(Result.FAIL, result(with(base, Acceptance.SELF_DAMAGE, 0, 20, 20), meteor, "S3"));
        // Meteor's median 20 (not its lowest run, 0): ++ at 17 passes.
        assertEquals(Result.PASS, result(with(base, Acceptance.SELF_DAMAGE, 17, 17, 30), meteor, "S3"));
    }

    // --- O1: damage dealt, never worse beyond max(1, 15 %) ------------------------------------------

    @Test
    void o1AllowsFifteenPercentLessDamage() {
        // Meteor 20: the limit is 17. A drop caused by the budget is a drop like any other.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.DAMAGE_DEALT, 20, 20, 20);
        assertEquals(Result.PASS, result(good(Acceptance.DAMAGE_DEALT, 17, 17, 17), meteor, "O1"));
        assertEquals(Result.FAIL, result(good(Acceptance.DAMAGE_DEALT, 16.75, 16.75, 16.75), meteor, "O1"));
    }

    @Test
    void o1AllowsOneLessWhenFifteenPercentIsLess() {
        // Meteor 4: 15 % is 0.6, the noise floor 1 wins, the limit is 3.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.DAMAGE_DEALT, 4, 4, 4);
        assertEquals(Result.PASS, result(good(Acceptance.DAMAGE_DEALT, 3, 3, 3), meteor, "O1"));
        assertEquals(Result.FAIL, result(good(Acceptance.DAMAGE_DEALT, 2.75, 2.75, 2.75), meteor, "O1"));
    }

    @Test
    void o1UsesMedians() {
        List<Map<String, Double>> meteor = meteorWith(Acceptance.DAMAGE_DEALT, 0, 20, 40);
        assertEquals(Result.PASS, result(good(Acceptance.DAMAGE_DEALT, 0, 17, 18), meteor, "O1"));
        assertEquals(Result.FAIL, result(good(Acceptance.DAMAGE_DEALT, 40, 16, 16), meteor, "O1"));
    }

    // --- O2: sparring pops, at most one fewer ---------------------------------------------------------

    @Test
    void o2AllowsOnePopFewer() {
        List<Map<String, Double>> meteor = meteorWith(Acceptance.SPARRING_POPS, 2, 2, 2);
        assertEquals(Result.PASS, result(good(Acceptance.SPARRING_POPS, 1, 1, 1), meteor, "O2"));
        assertEquals(Result.FAIL, result(good(Acceptance.SPARRING_POPS, 0.5, 0.5, 0.5), meteor, "O2"));
        // The median: 0, 1, 3 is 1.
        assertEquals(Result.PASS, result(good(Acceptance.SPARRING_POPS, 0, 1, 3), meteor, "O2"));
    }

    // --- O3: runs without a pop, summed ----------------------------------------------------------------

    @Test
    void o3ComparesTheSumOfRunsWithoutAPop() {
        List<Map<String, Double>> meteorAllPopped = meteorWith(Acceptance.NO_POP_RUNS, 0, 0, 0);
        // One ++ run without a pop against none: worse, although the median is 0 on both sides.
        assertEquals(Result.FAIL, result(good(Acceptance.NO_POP_RUNS, 0, 1, 0), meteorAllPopped, "O3"));
        assertEquals(Result.PASS, result(good(Acceptance.NO_POP_RUNS, 0, 1, 0), meteorWith(Acceptance.NO_POP_RUNS, 1, 0, 0), "O3"));
        assertEquals(Result.FAIL, result(good(Acceptance.NO_POP_RUNS, 1, 1, 0), meteorWith(Acceptance.NO_POP_RUNS, 1, 0, 0), "O3"));
        assertEquals(Result.PASS, result(good(Acceptance.NO_POP_RUNS, 0, 0, 0), meteorWith(Acceptance.NO_POP_RUNS, 1, 1, 1), "O3"));
    }

    // --- O4: the first pop, late by at most max(0.25 s, 15 %), when both popped ----------------------

    @Test
    void o4AllowsAQuarterSecondLaterWhenFifteenPercentIsLess() {
        // Meteor 1 s: 15 % is 0.15, the floor 0.25 wins, the limit 1.25.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.FIRST_POP_S, 1, 1, 1);
        assertEquals(Result.PASS, result(good(Acceptance.FIRST_POP_S, 1.25, 1.25, 1.25), meteor, "O4"));
        assertEquals(Result.FAIL, result(good(Acceptance.FIRST_POP_S, 1.5, 1.5, 1.5), meteor, "O4"));
    }

    @Test
    void o4AllowsFifteenPercentLaterWhenThatIsMore() {
        // Meteor 20 s: the limit is 23.
        List<Map<String, Double>> meteor = meteorWith(Acceptance.FIRST_POP_S, 20, 20, 20);
        assertEquals(Result.PASS, result(good(Acceptance.FIRST_POP_S, 23, 23, 23), meteor, "O4"));
        assertEquals(Result.FAIL, result(good(Acceptance.FIRST_POP_S, 23.25, 23.25, 23.25), meteor, "O4"));
    }

    @Test
    void o4DoesNotApplyUnlessBothPopped() {
        List<Map<String, Double>> neverPopped = three(run(20, 0, null, 8, 0, 9));
        assertEquals(Result.NOT_APPLICABLE, result(neverPopped, three(meteorRun()), "O4"));
        assertEquals(Result.NOT_APPLICABLE, result(three(goodRun()), three(run(20, 0, null, 16, 0, 4)), "O4"));
    }

    @Test
    void o4TakesTheMedianOfThePoppedRunsOnly() {
        // ++ popped in two runs, at 0.5 and 2: their median is 1.25, the limit against Meteor's 1 s.
        List<Map<String, Double>> capp = List.of(goodRunPoppingAt(0.5), run(20, 0, null, 8, 0, 9), goodRunPoppingAt(2));
        assertEquals(Result.PASS, result(capp, meteorWith(Acceptance.FIRST_POP_S, 1, 1, 1), "O4"));
        capp = List.of(goodRunPoppingAt(0.5), run(20, 0, null, 8, 0, 9), goodRunPoppingAt(2.25));
        assertEquals(Result.FAIL, result(capp, meteorWith(Acceptance.FIRST_POP_S, 1, 1, 1), "O4"));
    }

    private static Map<String, Double> goodRunPoppingAt(double seconds) {
        Map<String, Double> m = new HashMap<>(goodRun());
        m.put(Acceptance.FIRST_POP_S, seconds);
        return m;
    }

    // --- INCOMPLETE and the death rule (P6, Q5) ------------------------------------------------------

    @Test
    void withoutMeteorsScenarioInThisInvocationItIsIncomplete() {
        Outcome outcome = Acceptance.judge(capp(three(goodRun())), Side.absent(CA));
        assertEquals(Verdict.INCOMPLETE, outcome.verdict());
        assertEquals(List.of(), outcome.rules());
        assertEquals("ca-still did not run in this invocation", outcome.reason());
    }

    @Test
    void eitherSideNotDoneIsIncomplete() {
        Outcome meteorError = Acceptance.judge(capp(three(goodRun())), new Side(CA, "ERROR", List.of(meteorRun()), false));
        assertEquals(Verdict.INCOMPLETE, meteorError.verdict());
        assertEquals("ca-still is ERROR", meteorError.reason());

        Outcome cappPending = Acceptance.judge(new Side(CAPP, "PENDING", List.of(goodRun()), false), meteor(three(meteorRun())));
        assertEquals(Verdict.INCOMPLETE, cappPending.verdict());
        assertEquals("capp-still is PENDING", cappPending.reason());

        // A ++ run in ERROR that is not a death: INCOMPLETE, never ACCEPT, although the numbers there are good.
        Outcome cappError = Acceptance.judge(new Side(CAPP, "ERROR", List.of(goodRun(), goodRun()), false), meteor(three(meteorRun())));
        assertEquals(Verdict.INCOMPLETE, cappError.verdict());
    }

    @Test
    void anythingButThreeRunsIsIncomplete() {
        Outcome two = judge(List.of(goodRun(), goodRun()), three(meteorRun()));
        assertEquals(Verdict.INCOMPLETE, two.verdict());
        assertEquals("capp-still has 2 DONE runs, not 3", two.reason());
        List<Map<String, Double>> four = new ArrayList<>(three(meteorRun()));
        four.add(meteorRun());
        assertEquals(Verdict.INCOMPLETE, judge(three(goodRun()), four).verdict());
    }

    @Test
    void aRunWithoutAMetricTheRulesNeedIsIncomplete() {
        List<Map<String, Double>> runs = three(goodRun());
        runs.get(1).remove(Acceptance.MIN_HEALTH);
        Outcome outcome = judge(runs, three(meteorRun()));
        assertEquals(Verdict.INCOMPLETE, outcome.verdict());
        assertEquals("a run of capp-still has no min_health", outcome.reason());
        // first_pop_s is only in a run that popped: its absence is no gap.
        assertEquals(Verdict.ACCEPT, judge(three(run(20, 0, null, 8, 0, 9)), three(run(20, 0, null, 16, 0, 4))).verdict());
    }

    @Test
    void ourDeathInACrystalAuraPlusPlusRunIsAReject() {
        Side died = new Side(CAPP, "ERROR", List.of(goodRun(), goodRun()), true);
        Outcome outcome = Acceptance.judge(died, meteor(three(meteorRun())));
        assertEquals(Verdict.REJECT, outcome.verdict());
        assertEquals("our player died in a capp-still run", outcome.reason());
        assertEquals(List.of(), outcome.rules());
        // Whatever Meteor's side is: the death alone decides.
        assertEquals(Verdict.REJECT, Acceptance.judge(died, Side.absent(CA)).verdict());
        assertEquals(Verdict.REJECT, Acceptance.judge(died, new Side(CA, "ERROR", List.of(), true)).verdict());
    }

    @Test
    void aDeathInMeteorsRunIsIncompleteNotAReject() {
        Outcome outcome = Acceptance.judge(capp(three(goodRun())), new Side(CA, "ERROR", List.of(meteorRun(), meteorRun()), true));
        assertEquals(Verdict.INCOMPLETE, outcome.verdict());
    }

    // --- What the report shows --------------------------------------------------------------------------

    @Test
    void everyRuleSaysWhatItCompared() {
        Outcome outcome = judge(three(goodRun()), three(meteorRun()));
        assertEquals("worst run self_pops: ++ 0.00, Meteor 0.00", rule(outcome, "S1").detail());
        assertEquals("lowest run min_health: ++ 9.00, needs >= 1.50; median min_health: ++ 9.00, Meteor 4.00, needs >= 3.00",
            rule(outcome, "S2").detail());
        assertEquals("median self_damage: ++ 8.00, Meteor 16.00, needs <= 13.60; or median min_health: ++ 9.00, needs >= 5.00",
            rule(outcome, "S3").detail());
        assertEquals("median damage_dealt: ++ 20.00, Meteor 20.00, needs >= 17.00", rule(outcome, "O1").detail());
        assertEquals("median sparring_pops: ++ 2.00, Meteor 2.00, needs >= 1.00", rule(outcome, "O2").detail());
        assertEquals("no_pop_runs: ++ 0, Meteor 0", rule(outcome, "O3").detail());
        assertEquals("median first_pop_s: ++ 1.00, Meteor 1.00, needs <= 1.25", rule(outcome, "O4").detail());

        Outcome quiet = judge(three(run(0, 0, null, 0, 0, 20)), three(run(0, 0, null, 1.5, 0, 20)));
        assertEquals("Meteor median self_damage 1.50, below 2.00", rule(quiet, "S3").detail());
        assertEquals("not both popped: ++ in 0 runs, Meteor in 0 runs", rule(quiet, "O4").detail());
    }

    @Test
    void noDetailOrReasonLooksLikeAPosition() {
        double[] values = {0, 1, -1, 2, 2.5, 13.6, 20, 100, 0.001, 1e6};
        List<String> texts = new ArrayList<>();
        for (double a : values) {
            for (double b : values) {
                Outcome outcome = judge(three(run(a, a, a, a, a, b)), three(run(b, b, b, b, b, a)));
                texts.add(String.valueOf(outcome.reason()));
                outcome.rules().forEach(r -> texts.add(r.detail()));
            }
        }
        for (String text : texts) assertFalse(PositionLike.in(text), text);
        assertTrue(PositionLike.in("12 64 -3"), "the pattern itself");
    }

    @Test
    void theMetricNamesAreTheBenchReportsNames() throws IOException {
        // The bench's Metrics table is not on the test classpath; its source is read instead.
        String metrics = Files.readString(Path.of("src", "gametest", "java", "com", "xploits", "bench", "Metrics.java"),
            StandardCharsets.UTF_8);
        for (String name : Acceptance.METRICS) {
            assertTrue(metrics.contains("= \"" + name + "\";"), "Metrics has no " + name);
        }
        assertEquals(7, Acceptance.METRICS.size());
    }
}
