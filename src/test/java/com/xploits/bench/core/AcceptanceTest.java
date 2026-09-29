package com.xploits.bench.core;

import com.xploits.bench.core.Acceptance.Kind;
import com.xploits.bench.core.Acceptance.Outcome;
import com.xploits.bench.core.Acceptance.Result;
import com.xploits.bench.core.Acceptance.Rule;
import com.xploits.bench.core.Acceptance.Side;
import com.xploits.bench.core.Acceptance.Verdict;
import com.xploits.pvp.crystal.core.RiskLevel;
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
        m.put(Acceptance.PLACEMENTS_PER_S, 0.5);
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

    private static Outcome judge(List<Map<String, Double>> capp, List<Map<String, Double>> meteor, RiskLevel level) {
        return Acceptance.judge(capp(capp), meteor(meteor), level);
    }

    private static Rule rule(Outcome outcome, String id) {
        return outcome.rules().stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("no rule " + id + " in " + outcome));
    }

    private static Result result(List<Map<String, Double>> capp, List<Map<String, Double>> meteor, String id) {
        return rule(judge(capp, meteor), id).result();
    }

    private static Result result(List<Map<String, Double>> capp, List<Map<String, Double>> meteor, RiskLevel level, String id) {
        return rule(judge(capp, meteor, level), id).result();
    }

    // --- The whole verdict --------------------------------------------------------------------------

    @Test
    void aRunThatPassesEveryRuleIsAccepted() {
        Outcome outcome = judge(three(goodRun()), three(meteorRun()));
        assertEquals(Verdict.ACCEPT, outcome.verdict());
        assertEquals(List.of("S1", "S2", "S3", "S4", "O1", "O2", "O3", "O4"), outcome.rules().stream().map(Rule::id).toList());
        assertEquals(List.of(Kind.SAFETY, Kind.SAFETY, Kind.SAFETY, Kind.SAFETY, Kind.OFFENSE, Kind.OFFENSE, Kind.OFFENSE, Kind.OFFENSE),
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

    // --- S3 (R3-10): not applicable when Meteor never went below the level's reserve ------------------

    @Test
    void s3DoesNotApplyWhenMeteorNeverWentBelowTheLevelsReserve() {
        // Balanced's reserve is 3.5; Meteor's lowest run is 3.52, just above it. ++ identical to Meteor:
        // nothing for the budget to prevent, so S3 is not applicable and the pair still accepts.
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 16, 0, 3.52));
        Outcome outcome = judge(same, same, RiskLevel.BALANCED);
        assertEquals(Result.NOT_APPLICABLE, rule(outcome, "S3").result());
        assertEquals("Meteor never went below the reserve 3.50 (lowest 3.52): nothing to make safer",
            rule(outcome, "S3").detail());
        assertEquals(Verdict.ACCEPT, outcome.verdict());
        // The exception is S3's alone: S1 and S2 are still judged (never NOT_APPLICABLE) although Meteor
        // never went below the reserve either.
        assertEquals(Result.PASS, rule(outcome, "S1").result());
        assertEquals(Result.PASS, rule(outcome, "S2").result());
    }

    @Test
    void s3StillAppliesWhenMeteorWentBelowTheLevelsReserve() {
        // Meteor's lowest run is 3.37, below Balanced's 3.5: S3 is judged exactly as today, and an
        // identical ++ fails it (no improvement) exactly as without a risk level.
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 16, 0, 3.37));
        Outcome outcome = judge(same, same, RiskLevel.BALANCED);
        assertEquals(Result.FAIL, rule(outcome, "S3").result());
        assertEquals(Verdict.REJECT, outcome.verdict());
        assertEquals("failed: S3", outcome.reason());
        // S1 and S2 are untouched by the new exception: still judged and still passing.
        assertEquals(Result.PASS, rule(outcome, "S1").result());
        assertEquals(Result.PASS, rule(outcome, "S2").result());
    }

    @Test
    void s3sExceptionReadsTheLevelsOwnReserveFromRiskLevel() {
        // The same Meteor lowest run, 3.37: below Aggressive's reserve (2) it does not apply; below
        // Safe's reserve (5) it does not apply either... the other way around. Aggressive's reserve is 2,
        // so 3.37 is above it (not applicable); Safe's reserve is 5, so 3.37 is below it (judged).
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 16, 0, 3.37));
        assertEquals(Result.NOT_APPLICABLE, result(same, same, RiskLevel.AGGRESSIVE, "S3"));
        assertEquals(Result.FAIL, result(same, same, RiskLevel.SAFE, "S3"));
    }

    @Test
    void s3sExceptionIsExactAtTheBoundary() {
        // Meteor's lowest run equals Balanced's reserve exactly: not applicable, not judged.
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 16, 0, 3.5));
        assertEquals(Result.NOT_APPLICABLE, result(same, same, RiskLevel.BALANCED, "S3"));
    }

    @Test
    void s3sExceptionCoversTheAboveShapeAtEveryLevel() {
        // Meteor's self damage is above S3_FROM (2.08 >= 2), but its lowest run (18.29) is well above even
        // Safe's reserve (5): nothing there for any level to make safer.
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 2.08, 0, 18.29));
        for (RiskLevel level : List.of(RiskLevel.SAFE, RiskLevel.BALANCED, RiskLevel.AGGRESSIVE)) {
            assertEquals(Result.NOT_APPLICABLE, result(same, same, level, "S3"));
        }
    }

    @Test
    void s3sExceptionDoesNotExcuseAFailingOffenseRule() {
        // Same safety numbers as the first test (S3 not applicable), but ++ deals half the damage: O1
        // fails and the pair is rejected regardless of S3.
        List<Map<String, Double>> meteorRuns = three(run(20, 2, 1.0, 16, 0, 3.52));
        List<Map<String, Double>> cappRuns = three(run(10, 2, 1.0, 16, 0, 3.52));
        Outcome outcome = judge(cappRuns, meteorRuns, RiskLevel.BALANCED);
        assertEquals(Result.NOT_APPLICABLE, rule(outcome, "S3").result());
        assertEquals(Result.FAIL, rule(outcome, "O1").result());
        assertEquals(Verdict.REJECT, outcome.verdict());
        assertEquals("failed: O1", outcome.reason());
    }

    @Test
    void s3sExceptionUsesTheLowestRunNotTheMedian() {
        // Meteor's min_health runs are 3.0, 4.0, 4.0: median 4.0 is above Balanced's 3.5, but the lowest
        // run, 3.0, is below it, so the exception must not apply (a median-based mutant would say it does).
        List<Map<String, Double>> same = with(meteorRun(), Acceptance.MIN_HEALTH, 3.0, 4.0, 4.0);
        Outcome outcome = judge(same, same, RiskLevel.BALANCED);
        assertEquals(Result.FAIL, rule(outcome, "S3").result());
    }

    @Test
    void s3sExceptionReadsMeteorsLowestRunNotPlusPluss() {
        // Meteor's lowest run is 3.0, below Balanced's 3.5: the exception must not apply, whatever ++'s own
        // lowest run is (a mutant reading ++'s runs instead of Meteor's would say it does, here at 4.0).
        List<Map<String, Double>> meteorRuns = meteorWith(Acceptance.MIN_HEALTH, 3.0, 3.0, 3.0);
        List<Map<String, Double>> cappRuns = meteorWith(Acceptance.MIN_HEALTH, 4.0, 4.0, 4.0);
        Outcome outcome = judge(cappRuns, meteorRuns, RiskLevel.BALANCED);
        // Judged, not skipped: ++'s median min_health (4.0) meets Meteor's median (3.0) plus one, so S3
        // passes on the min-health branch instead of being not applicable.
        assertEquals(Result.PASS, rule(outcome, "S3").result());
    }

    @Test
    void s3KeepsTodaysRuleForCustomWhoseReserveIsAUserSetting() {
        // CUSTOM's reserve is a player setting this pure core never sees (RiskLevel.CUSTOM's own field is
        // NaN); the exception must never apply to it, whatever Meteor's lowest run is. Here it is 100, far
        // above any fixed level's reserve, yet CUSTOM still gets today's S3 (judged, not skipped) exactly as
        // no level at all: identical ++ shows no improvement, so it fails.
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 16, 0, 100));
        Outcome outcome = judge(same, same, RiskLevel.CUSTOM);
        assertEquals(Result.FAIL, rule(outcome, "S3").result());
    }

    @Test
    void s3sExceptionIsCheckedBeforeTheSelfDamageOneWhenBothWouldApply() {
        // Meteor's self damage median (1.5) is below S3_FROM (2.0) AND its lowest run (4.0) clears
        // Balanced's reserve (3.5): both NOT_APPLICABLE conditions independently hold. The reserve
        // exception must be checked first, so its detail is the one that shows; swapping the two `if`s
        // would still return NOT_APPLICABLE, only with the self_damage detail instead.
        List<Map<String, Double>> same = three(run(20, 2, 1.0, 1.5, 0, 4.0));
        Outcome outcome = judge(same, same, RiskLevel.BALANCED);
        assertEquals(Result.NOT_APPLICABLE, rule(outcome, "S3").result());
        assertEquals("Meteor never went below the reserve 3.50 (lowest 4.00): nothing to make safer",
            rule(outcome, "S3").detail());
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

    // --- NOT_APPLICABLE: a pair where no crystal was ever placed (round 2) -------------------------------

    @Test
    void aPairWhereNeitherSidePlacedACrystalIsNotApplicable() {
        Outcome outcome = judge(good(Acceptance.PLACEMENTS_PER_S, 0, 0, 0), meteorWith(Acceptance.PLACEMENTS_PER_S, 0, 0, 0));
        assertEquals(Verdict.NOT_APPLICABLE, outcome.verdict());
        assertEquals("neither capp-still nor ca-still placed a crystal in any run", outcome.reason());
        // Not evidence either way: no rule is shown as passed.
        assertEquals(List.of(), outcome.rules());
    }

    @Test
    void oneCrystalOnEitherSideInAnyRunMakesThePairApplicable() {
        // Meteor placed in one run only, ++ never: judged by the rules (the numbers here pass them all).
        Outcome meteorOnce = judge(good(Acceptance.PLACEMENTS_PER_S, 0, 0, 0), meteorWith(Acceptance.PLACEMENTS_PER_S, 0, 0.5, 0));
        assertEquals(Verdict.ACCEPT, meteorOnce.verdict());
        assertEquals(8, meteorOnce.rules().size());
        // ++ placed in one run only, Meteor never: judged too.
        Outcome cappOnce = judge(good(Acceptance.PLACEMENTS_PER_S, 0, 0, 1.0 / 30), meteorWith(Acceptance.PLACEMENTS_PER_S, 0, 0, 0));
        assertEquals(Verdict.ACCEPT, cappOnce.verdict());
        assertEquals(8, cappOnce.rules().size());
    }

    @Test
    void aDeathIsARejectEvenWhereNoCrystalWasPlaced() {
        Side died = new Side(CAPP, "ERROR", good(Acceptance.PLACEMENTS_PER_S, 0, 0), true);
        Outcome outcome = Acceptance.judge(died, meteor(meteorWith(Acceptance.PLACEMENTS_PER_S, 0, 0, 0)));
        assertEquals(Verdict.REJECT, outcome.verdict());
    }

    @Test
    void aMissingPlacementCountIsIncompleteNotNotApplicable() {
        List<Map<String, Double>> runs = good(Acceptance.PLACEMENTS_PER_S, 0, 0, 0);
        runs.get(2).remove(Acceptance.PLACEMENTS_PER_S);
        Outcome outcome = judge(runs, meteorWith(Acceptance.PLACEMENTS_PER_S, 0, 0, 0));
        assertEquals(Verdict.INCOMPLETE, outcome.verdict());
        assertEquals("a run of capp-still has no placements_per_s", outcome.reason());
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
        texts.add(judge(good(Acceptance.PLACEMENTS_PER_S, 0, 0, 0), meteorWith(Acceptance.PLACEMENTS_PER_S, 0, 0, 0)).reason());
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
        assertEquals(8, Acceptance.METRICS.size());
    }

    // --- Task A3: fight scenarios (F1 result, F2 net_pops, F3 the reserve on min_health_after_own_hit) ----

    /** One fight run's numbers; {@code minHealthAfterOwnHit} null when we took no hit from our own crystal. */
    private static Map<String, Double> fightRun(double result, double popsDealt, double popsTaken, double netPops,
                                                 double damage, double selfDamage, double minHealth,
                                                 Double minHealthAfterOwnHit) {
        Map<String, Double> m = new HashMap<>();
        m.put(Acceptance.RESULT, result);
        m.put("pops_dealt", popsDealt);
        m.put("pops_taken", popsTaken);
        m.put(Acceptance.NET_POPS, netPops);
        m.put(Acceptance.DAMAGE_DEALT, damage);
        m.put(Acceptance.SELF_DAMAGE, selfDamage);
        m.put(Acceptance.MIN_HEALTH, minHealth);
        m.put(Acceptance.PLACEMENTS_PER_S, 0.5);
        if (minHealthAfterOwnHit != null) m.put(Acceptance.MIN_HEALTH_AFTER_OWN_HIT, minHealthAfterOwnHit);
        return m;
    }

    private static Map<String, Double> meteorFightWin() {
        return fightRun(1, 3, 1, 2, 20, 8, 9, null);
    }

    private static Map<String, Double> cappFightWin() {
        return fightRun(1, 3, 1, 2, 20, 6, 9, null);
    }

    private static List<Map<String, Double>> threeFights(Map<String, Double> run) {
        return List.of(run, new HashMap<>(run), new HashMap<>(run));
    }

    private static List<Map<String, Double>> fightWith(Map<String, Double> base, String metric, double... values) {
        return with(base, metric, values);
    }

    @Test
    void aFightPairIsDetectedFromTheResultMetricAndGetsFRulesNotSAndO() {
        Outcome outcome = judge(threeFights(cappFightWin()), threeFights(meteorFightWin()));
        assertEquals(List.of("F1", "F2", "F3", "F4"), outcome.rules().stream().map(Rule::id).toList());
        assertEquals(Verdict.ACCEPT, outcome.verdict());
    }

    @Test
    void anOldStyleFightlessPairStillGetsTheOriginalSevenRules() {
        // Unchanged: a pair whose runs carry the original (non-fight) metrics keeps S1-O4.
        Outcome outcome = judge(three(goodRun()), three(meteorRun()));
        assertEquals(List.of("S1", "S2", "S3", "S4", "O1", "O2", "O3", "O4"), outcome.rules().stream().map(Rule::id).toList());
    }

    // --- F1: result, median, never worse -------------------------------------------------------------

    @Test
    void f1FailsWhenPlusPlussMedianResultIsWorseThanMeteors() {
        // Meteor wins every run (median 1); ++ loses every run (median -1).
        List<Map<String, Double>> meteor = threeFights(meteorFightWin());
        List<Map<String, Double>> capp = fightWith(cappFightWin(), Acceptance.RESULT, -1, -1, -1);
        assertEquals(Result.FAIL, result(capp, meteor, "F1"));
    }

    @Test
    void f1PassesWhenPlusPlusDoesNotLoseWhereMeteorWins() {
        List<Map<String, Double>> meteor = threeFights(meteorFightWin());
        assertEquals(Result.PASS, result(threeFights(cappFightWin()), meteor, "F1"));
    }

    @Test
    void f1PassesOnAnEqualOrBetterMedian() {
        // Meteor draws (median 0); ++ wins (median 1): never worse, so it passes.
        List<Map<String, Double>> meteor = fightWith(meteorFightWin(), Acceptance.RESULT, 0, 0, 0);
        List<Map<String, Double>> capp = fightWith(cappFightWin(), Acceptance.RESULT, 1, 1, 1);
        assertEquals(Result.PASS, result(capp, meteor, "F1"));
        // A draw against a draw: equal is not worse.
        List<Map<String, Double>> cappDraw = fightWith(cappFightWin(), Acceptance.RESULT, 0, 0, 0);
        assertEquals(Result.PASS, result(cappDraw, meteor, "F1"));
    }

    // --- F2: net_pops, never worse beyond a noise margin of 1 ------------------------------------------

    @Test
    void f2AllowsOneNetPopFewer() {
        List<Map<String, Double>> meteor = fightWith(meteorFightWin(), Acceptance.NET_POPS, 2, 2, 2);
        assertEquals(Result.PASS, result(fightWith(cappFightWin(), Acceptance.NET_POPS, 1, 1, 1), meteor, "F2"));
        assertEquals(Result.FAIL, result(fightWith(cappFightWin(), Acceptance.NET_POPS, 0.5, 0.5, 0.5), meteor, "F2"));
    }

    @Test
    void f2UsesMedians() {
        List<Map<String, Double>> meteor = fightWith(meteorFightWin(), Acceptance.NET_POPS, 2, 2, 2);
        // ++'s median (0, 1, 3 -> 1) is one below Meteor's 2: passes.
        assertEquals(Result.PASS, result(fightWith(cappFightWin(), Acceptance.NET_POPS, 0, 1, 3), meteor, "F2"));
    }

    // --- F3: the reserve on min_health_after_own_hit, every run, per level -----------------------------

    @Test
    void f3FailsWhenAnyRunsMinHealthAfterOwnHitIsBelowTheLevelsReserve() {
        // Balanced's reserve is 3.5. One run at 3.4 (below), two well above.
        List<Map<String, Double>> capp = List.of(
            fightRun(1, 3, 1, 2, 20, 6, 9, 9.0), fightRun(1, 3, 1, 2, 20, 6, 9, 3.4), fightRun(1, 3, 1, 2, 20, 6, 9, 9.0));
        Outcome outcome = judge(capp, threeFights(meteorFightWin()), RiskLevel.BALANCED);
        assertEquals(Result.FAIL, rule(outcome, "F3").result());
    }

    @Test
    void f3PassesWhenEveryRunsMinHealthAfterOwnHitMeetsTheReserve() {
        List<Map<String, Double>> capp = List.of(
            fightRun(1, 3, 1, 2, 20, 6, 9, 3.5), fightRun(1, 3, 1, 2, 20, 6, 9, 9.0), fightRun(1, 3, 1, 2, 20, 6, 9, 5.0));
        Outcome outcome = judge(capp, threeFights(meteorFightWin()), RiskLevel.BALANCED);
        assertEquals(Result.PASS, rule(outcome, "F3").result());
    }

    @Test
    void f3TreatsAZeroAfterAsALethalOwnHitAndFailsAtAnyPositiveReserve() {
        // MinHealthAfterOwnHit's own javadoc: after == 0 means a lethal own hit, never a real survived value.
        List<Map<String, Double>> capp = List.of(
            fightRun(1, 3, 1, 2, 20, 6, 9, 0.0), fightRun(1, 3, 1, 2, 20, 6, 9, 9.0), fightRun(1, 3, 1, 2, 20, 6, 9, 9.0));
        Outcome outcome = judge(capp, threeFights(meteorFightWin()), RiskLevel.AGGRESSIVE);
        assertEquals(Result.FAIL, rule(outcome, "F3").result());
    }

    @Test
    void f3IsNotApplicableWhenNoRunTookAHitFromItsOwnCrystal() {
        Outcome outcome = judge(threeFights(cappFightWin()), threeFights(meteorFightWin()), RiskLevel.BALANCED);
        assertEquals(Result.NOT_APPLICABLE, rule(outcome, "F3").result());
    }

    @Test
    void f3IsNotApplicableWithoutAFixedLevel() {
        List<Map<String, Double>> capp = List.of(
            fightRun(1, 3, 1, 2, 20, 6, 9, 0.0), fightRun(1, 3, 1, 2, 20, 6, 9, 9.0), fightRun(1, 3, 1, 2, 20, 6, 9, 9.0));
        // No level at all, and CUSTOM (a player setting this pure core never sees): F3 cannot be judged.
        assertEquals(Result.NOT_APPLICABLE, rule(judge(capp, threeFights(meteorFightWin())), "F3").result());
        assertEquals(Result.NOT_APPLICABLE,
            rule(judge(capp, threeFights(meteorFightWin()), RiskLevel.CUSTOM), "F3").result());
    }

    @Test
    void f3ReadsTheReserveFromEachLevel() {
        // A run at exactly 2.0 (SelfBudget.FLOOR): passes Aggressive (reserve 2), fails Balanced (3.5) and Safe (5).
        List<Map<String, Double>> capp = List.of(
            fightRun(1, 3, 1, 2, 20, 6, 9, 2.0), fightRun(1, 3, 1, 2, 20, 6, 9, 2.0), fightRun(1, 3, 1, 2, 20, 6, 9, 2.0));
        Side cappSide = capp(capp);
        Side meteorSide = meteor(threeFights(meteorFightWin()));
        assertEquals(Result.PASS, rule(Acceptance.judge(cappSide, meteorSide, RiskLevel.AGGRESSIVE), "F3").result());
        assertEquals(Result.FAIL, rule(Acceptance.judge(cappSide, meteorSide, RiskLevel.BALANCED), "F3").result());
        assertEquals(Result.FAIL, rule(Acceptance.judge(cappSide, meteorSide, RiskLevel.SAFE), "F3").result());
    }

    // --- S4 (task B0b/B0c): finishing blows in the older scenarios are pop-grade ---------------------

    private static List<Map<String, Double>> oldRunsWith(Double healthAfter, Double pops) {
        Map<String, Double> odd = goodRun();
        if (healthAfter != null) odd.put(Acceptance.MIN_HEALTH_AFTER_FINISHING_HIT, healthAfter);
        if (pops != null) odd.put(Acceptance.FINISHING_POPS, pops);
        return List.of(goodRun(), odd, goodRun());
    }

    @Test
    void s4PassesWithoutAnyFinishingBlow() {
        assertEquals(Result.PASS, result(three(goodRun()), three(meteorRun()), "S4"));
    }

    @Test
    void s4PassesOnAFinishingBlowThatLeftUsAtTwoOrMoreAndDidNotPopUs() {
        assertEquals(Result.PASS, result(oldRunsWith(2.0, 0.0), three(meteorRun()), "S4"));
        assertEquals(Result.PASS, result(oldRunsWith(4.5, 0.0), three(meteorRun()), "S4"));
    }

    @Test
    void s4FailsOnAFinishingBlowThatLeftUsBelowTwo() {
        assertEquals(Result.FAIL, result(oldRunsWith(1.9, 0.0), three(meteorRun()), "S4"));
    }

    @Test
    void s4FailsOnAFinishingBlowThatPoppedUs() {
        assertEquals(Result.FAIL, result(oldRunsWith(6.0, 1.0), three(meteorRun()), "S4"));
    }

    @Test
    void s4IsSafetyAndRejectsTheVerdictWhenItFails() {
        Outcome outcome = judge(oldRunsWith(1.0, 0.0), three(meteorRun()));
        assertEquals(Kind.SAFETY, rule(outcome, "S4").kind());
        assertEquals(Verdict.REJECT, outcome.verdict());
    }

    @Test
    void f4FailsOnAPopGradeViolation() {
        Map<String, Double> run = finishingRun(3.0, 3.0, null);
        run.put(Acceptance.FINISHING_POP_GRADE_VIOLATIONS, 1.0);
        assertEquals(Result.FAIL, f4(run));
        run.put(Acceptance.FINISHING_POP_GRADE_VIOLATIONS, 0.0);
        assertEquals(Result.PASS, f4(run));
    }

    // --- F4 (task B0b): the finishing blow keeps its promises, every run -----------------------------

    /** A fight run with the finishing-blow metrics, each null when absent. */
    private static Map<String, Double> finishingRun(Double totemsMin, Double healthAfter, Double diedWithTotem) {
        Map<String, Double> m = cappFightWin();
        if (totemsMin != null) m.put(Acceptance.TOTEMS_AT_FINISHING_HIT_MIN, totemsMin);
        if (healthAfter != null) m.put(Acceptance.MIN_HEALTH_AFTER_FINISHING_HIT, healthAfter);
        if (diedWithTotem != null) m.put(Acceptance.DIED_WITH_TOTEM, diedWithTotem);
        return m;
    }

    private static Result f4(Map<String, Double> one) {
        List<Map<String, Double>> capp = List.of(cappFightWin(), one, cappFightWin());
        return rule(judge(capp, threeFights(meteorFightWin()), RiskLevel.BALANCED), "F4").result();
    }

    @Test
    void f4PassesWhenNothingHappenedAtAll() {
        assertEquals(Result.PASS, f4(cappFightWin()));
    }

    @Test
    void f4PassesOnAFinishingHitWithASpareTotemThatLeftUsAlive() {
        assertEquals(Result.PASS, f4(finishingRun(2.0, 1.0, null)));
        assertEquals(Result.PASS, f4(finishingRun(5.0, 4.5, null)));
    }

    @Test
    void f4FailsWhenAFinishingHitHappenedWithFewerThanTwoTotems() {
        assertEquals(Result.FAIL, f4(finishingRun(1.0, 1.0, null)));
    }

    @Test
    void f4FailsWhenWeWereNotAliveAfterAFinishingHit() {
        assertEquals(Result.FAIL, f4(finishingRun(3.0, 0.0, null)));
    }

    @Test
    void f4FailsWhenARunEndedInOurDeathWhileWeCarriedATotem() {
        assertEquals(Result.FAIL, f4(finishingRun(null, null, 1.0)));
    }

    @Test
    void f4IsSafetyAndRejectsTheVerdictWhenItFails() {
        List<Map<String, Double>> capp = List.of(cappFightWin(), finishingRun(1.0, 1.0, null), cappFightWin());
        Outcome outcome = judge(capp, threeFights(meteorFightWin()), RiskLevel.BALANCED);
        assertEquals(Kind.SAFETY, rule(outcome, "F4").kind());
        assertEquals(Verdict.REJECT, outcome.verdict());
    }

    @Test
    void f3StillJudgesOrdinaryHitsOnlyWhichTheBenchAlreadyExcludedFinishingOnesFrom() {
        // A finishing hit's health never reaches F3: only min_health_after_own_hit does, and the bench leaves
        // finishing hits out of it. So a run with a low finishing hit and a healthy ordinary one passes F3.
        Map<String, Double> run = finishingRun(2.0, 1.0, null);
        run.put(Acceptance.MIN_HEALTH_AFTER_OWN_HIT, 9.0);
        List<Map<String, Double>> capp = List.of(cappFightWin(), run, cappFightWin());
        assertEquals(Result.PASS, rule(judge(capp, threeFights(meteorFightWin()), RiskLevel.SAFE), "F3").result());
    }

    @Test
    void aFightPairsDetailsNeverLookLikeAPosition() {
        List<Map<String, Double>> capp = List.of(
            fightRun(-1, 1, 3, -2, 5, 12, 1, 0.0), fightRun(0, 2, 2, 0, 10, 6, 4, 12.0),
            fightRun(1, 3, 1, 2, 20, 6, 9, 6.0));
        Outcome outcome = judge(capp, threeFights(meteorFightWin()), RiskLevel.SAFE);
        for (Rule r : outcome.rules()) assertFalse(PositionLike.in(r.detail()), r.detail());
    }
}
