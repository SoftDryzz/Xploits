package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.SelfBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;

/**
 * Whether crystal-aura++ is accepted against Meteor's crystal-aura on one arena (crystal-aura++ spec §4,
 * P6, Q5, Q6): a {@code capp-X} MEASURE judged against {@code ca-X} from the same bench invocation, each
 * with its {@value #RUNS} DONE runs. Pure: the bench report hands in the runs' numbers and shows what
 * comes out; the verdict never fails the bench by itself.
 *
 * <p>The rules, all of which must pass (or not apply) for an ACCEPT:
 * <table>
 *   <caption>Acceptance rules</caption>
 *   <tr><th>Rule</th><th>What must hold</th><th>Over</th></tr>
 *   <tr><td>S1</td><td>++'s worst {@code self_pops} &le; Meteor's worst</td><td>per-run extremes</td></tr>
 *   <tr><td>S2</td><td>every ++ run's {@code min_health} &ge; F &minus; 0.5, and ++'s median &ge; Meteor's
 *   &minus; 1.0</td><td>per-run extreme, then medians</td></tr>
 *   <tr><td>S3</td><td>only when Meteor's {@code self_damage} &ge; 2: ++'s &le; Meteor's &minus; max(1, 15 %),
 *   or ++'s {@code min_health} &ge; Meteor's + 1</td><td>medians</td></tr>
 *   <tr><td>O1</td><td>{@code damage_dealt} &ge; Meteor's &minus; max(1, 15 %), whatever caused a drop
 *   (the budget included)</td><td>medians</td></tr>
 *   <tr><td>O2</td><td>{@code sparring_pops} &ge; Meteor's &minus; 1</td><td>medians</td></tr>
 *   <tr><td>O3</td><td>{@code no_pop_runs} &le; Meteor's</td><td>sums</td></tr>
 *   <tr><td>O4</td><td>only when both popped: {@code first_pop_s} &le; Meteor's + max(0.25, 15 %)</td>
 *   <td>medians of the runs that popped</td></tr>
 * </table>
 * The margins are the bench's noise floors for those metrics; the safety rules S1 and S2's first half
 * have none: they must never be worse.
 *
 * <p>Before the rules: our player dying in a ++ run is a REJECT (Q5), whatever else. Otherwise either
 * side not DONE, not run in this invocation, without exactly {@value #RUNS} runs, or with a run that lacks
 * a metric the judgement reads, is INCOMPLETE, never ACCEPT (P6). Then a pair where neither side placed a
 * crystal in any run is NOT_APPLICABLE (round 2): its rules would all pass on nothing.
 */
public final class Acceptance {
    /** The runs each side needs. */
    public static final int RUNS = 3;
    /** The status of a scenario whose runs all finished. */
    public static final String DONE = "DONE";

    // The bench report's metric names (its Metrics table).
    public static final String DAMAGE_DEALT = "damage_dealt";
    public static final String SPARRING_POPS = "sparring_pops";
    public static final String FIRST_POP_S = "first_pop_s";
    public static final String NO_POP_RUNS = "no_pop_runs";
    public static final String SELF_DAMAGE = "self_damage";
    public static final String SELF_POPS = "self_pops";
    public static final String MIN_HEALTH = "min_health";
    public static final String PLACEMENTS_PER_S = "placements_per_s";
    /**
     * Every metric the judgement reads; {@link #FIRST_POP_S} is only in a run that popped, and
     * {@link #PLACEMENTS_PER_S} only decides whether the pair applies at all.
     */
    public static final List<String> METRICS = List.of(DAMAGE_DEALT, SPARRING_POPS, FIRST_POP_S, NO_POP_RUNS,
        SELF_DAMAGE, SELF_POPS, MIN_HEALTH, PLACEMENTS_PER_S);

    /** S2: no ++ run's min health below the budget's floor F less half a point. */
    public static final double LOWEST_MIN_HEALTH = SelfBudget.FLOOR - 0.5;
    /** S2's median margin, and S3's min-health alternative: the {@code min_health} noise floor. */
    public static final double MIN_HEALTH_MARGIN = 1.0;
    /** S3 applies from this median self damage of Meteor's. */
    public static final double S3_FROM = 2.0;
    /** S3 and O1: the {@code self_damage} and {@code damage_dealt} noise floor. */
    public static final double DAMAGE_FLOOR = 1.0;
    /** O2: the {@code sparring_pops} noise floor. */
    public static final double POPS_MARGIN = 1.0;
    /** O4: the {@code first_pop_s} noise floor. */
    public static final double FIRST_POP_FLOOR = 0.25;
    /** The share of Meteor's value a margin grows to when it is larger than the floor. */
    public static final int SHARE_PERCENT = 15;

    /**
     * NOT_APPLICABLE: neither side placed a crystal in any run, so the arena measured nothing about either
     * aura; it is not evidence, and the recommendation ({@link Recommendation}) leaves it out.
     */
    public enum Verdict { ACCEPT, REJECT, INCOMPLETE, NOT_APPLICABLE }

    public enum Kind { SAFETY, OFFENSE }

    public enum Result {
        PASS, FAIL, NOT_APPLICABLE
    }

    /**
     * One side of the comparison as this invocation left it.
     *
     * @param scenario its name
     * @param status   its report status ({@value #DONE} when its runs all finished), or null when it did
     *                 not run in this invocation
     * @param runs     the metrics of its DONE runs, in order
     * @param died     our player died in one of its runs
     */
    public record Side(String scenario, String status, List<Map<String, Double>> runs, boolean died) {
        public Side {
            Objects.requireNonNull(scenario);
            runs = runs.stream().map(Map::copyOf).toList();
        }

        /** A scenario that did not run in this invocation. */
        public static Side absent(String scenario) {
            return new Side(scenario, null, List.of(), false);
        }
    }

    /** One rule's result, with the numbers it compared in words (never three numbers in a row). */
    public record Rule(String id, Kind kind, Result result, String detail) {
    }

    /** The verdict, why when it is not ACCEPT, and the rules (none when they could not be judged). */
    public record Outcome(Verdict verdict, String reason, List<Rule> rules) {
        public Outcome {
            rules = List.copyOf(rules);
        }
    }

    private Acceptance() {
    }

    public static Outcome judge(Side capp, Side meteor) {
        if (capp.died()) return new Outcome(Verdict.REJECT, "our player died in a " + capp.scenario() + " run", List.of());
        for (Side side : List.of(capp, meteor)) {
            String gap = gap(side);
            if (gap != null) return new Outcome(Verdict.INCOMPLETE, gap, List.of());
        }
        if (neverPlaced(capp) && neverPlaced(meteor)) {
            return new Outcome(Verdict.NOT_APPLICABLE,
                "neither " + capp.scenario() + " nor " + meteor.scenario() + " placed a crystal in any run", List.of());
        }
        List<Rule> rules = List.of(s1(capp, meteor), s2(capp, meteor), s3(capp, meteor), o1(capp, meteor),
            o2(capp, meteor), o3(capp, meteor), o4(capp, meteor));
        List<String> failed = rules.stream().filter(r -> r.result() == Result.FAIL).map(Rule::id).toList();
        if (failed.isEmpty()) return new Outcome(Verdict.ACCEPT, null, rules);
        return new Outcome(Verdict.REJECT, "failed: " + String.join(", ", failed), rules);
    }

    /** Why this side cannot be judged, or null when it can. */
    private static String gap(Side side) {
        if (side.status() == null) return side.scenario() + " did not run in this invocation";
        if (!DONE.equals(side.status())) return side.scenario() + " is " + side.status();
        if (side.runs().size() != RUNS) return side.scenario() + " has " + side.runs().size() + " DONE runs, not " + RUNS;
        for (Map<String, Double> run : side.runs()) {
            for (String metric : METRICS) {
                if (metric.equals(FIRST_POP_S)) continue;
                Double value = run.get(metric);
                if (value == null || !Double.isFinite(value)) return "a run of " + side.scenario() + " has no " + metric;
            }
        }
        return null;
    }

    /** No crystal placed in any of the side's runs. */
    private static boolean neverPlaced(Side side) {
        return side.runs().stream().allMatch(run -> run.get(PLACEMENTS_PER_S) == 0);
    }

    // --- The rules ------------------------------------------------------------------------------------

    private static Rule s1(Side capp, Side meteor) {
        double ours = extreme(capp, SELF_POPS, Math::max);
        double theirs = extreme(meteor, SELF_POPS, Math::max);
        return new Rule("S1", Kind.SAFETY, pass(ours <= theirs),
            "worst run " + SELF_POPS + ": ++ " + n(ours) + ", Meteor " + n(theirs));
    }

    private static Rule s2(Side capp, Side meteor) {
        double lowest = extreme(capp, MIN_HEALTH, Math::min);
        double ours = median(capp, MIN_HEALTH);
        double theirs = median(meteor, MIN_HEALTH);
        double limit = theirs - MIN_HEALTH_MARGIN;
        return new Rule("S2", Kind.SAFETY, pass(lowest >= LOWEST_MIN_HEALTH && ours >= limit),
            "lowest run " + MIN_HEALTH + ": ++ " + n(lowest) + ", needs >= " + n(LOWEST_MIN_HEALTH)
                + "; median " + MIN_HEALTH + ": ++ " + n(ours) + ", Meteor " + n(theirs) + ", needs >= " + n(limit));
    }

    private static Rule s3(Side capp, Side meteor) {
        double theirs = median(meteor, SELF_DAMAGE);
        if (theirs < S3_FROM) {
            return new Rule("S3", Kind.SAFETY, Result.NOT_APPLICABLE,
                "Meteor median " + SELF_DAMAGE + " " + n(theirs) + ", below " + n(S3_FROM));
        }
        double ours = median(capp, SELF_DAMAGE);
        double limit = theirs - margin(theirs, DAMAGE_FLOOR);
        double oursHealth = median(capp, MIN_HEALTH);
        double healthLimit = median(meteor, MIN_HEALTH) + MIN_HEALTH_MARGIN;
        return new Rule("S3", Kind.SAFETY, pass(ours <= limit || oursHealth >= healthLimit),
            "median " + SELF_DAMAGE + ": ++ " + n(ours) + ", Meteor " + n(theirs) + ", needs <= " + n(limit)
                + "; or median " + MIN_HEALTH + ": ++ " + n(oursHealth) + ", needs >= " + n(healthLimit));
    }

    private static Rule o1(Side capp, Side meteor) {
        double theirs = median(meteor, DAMAGE_DEALT);
        return atLeast("O1", capp, DAMAGE_DEALT, theirs, theirs - margin(theirs, DAMAGE_FLOOR));
    }

    private static Rule o2(Side capp, Side meteor) {
        double theirs = median(meteor, SPARRING_POPS);
        return atLeast("O2", capp, SPARRING_POPS, theirs, theirs - POPS_MARGIN);
    }

    private static Rule o3(Side capp, Side meteor) {
        double ours = sum(capp, NO_POP_RUNS);
        double theirs = sum(meteor, NO_POP_RUNS);
        return new Rule("O3", Kind.OFFENSE, pass(ours <= theirs),
            NO_POP_RUNS + ": ++ " + whole(ours) + ", Meteor " + whole(theirs));
    }

    private static Rule o4(Side capp, Side meteor) {
        List<Double> ours = present(capp, FIRST_POP_S);
        List<Double> theirs = present(meteor, FIRST_POP_S);
        if (ours.isEmpty() || theirs.isEmpty()) {
            return new Rule("O4", Kind.OFFENSE, Result.NOT_APPLICABLE,
                "not both popped: ++ in " + ours.size() + " runs, Meteor in " + theirs.size() + " runs");
        }
        double oursMedian = median(ours);
        double theirsMedian = median(theirs);
        double limit = theirsMedian + margin(theirsMedian, FIRST_POP_FLOOR);
        return new Rule("O4", Kind.OFFENSE, pass(oursMedian <= limit),
            "median " + FIRST_POP_S + ": ++ " + n(oursMedian) + ", Meteor " + n(theirsMedian) + ", needs <= " + n(limit));
    }

    private static Rule atLeast(String id, Side capp, String metric, double theirs, double limit) {
        double ours = median(capp, metric);
        return new Rule(id, Kind.OFFENSE, pass(ours >= limit),
            "median " + metric + ": ++ " + n(ours) + ", Meteor " + n(theirs) + ", needs >= " + n(limit));
    }

    // --- Numbers --------------------------------------------------------------------------------------

    /** max(floor, 15 % of the value). */
    static double margin(double value, double floor) {
        return Math.max(floor, Math.abs(value) * SHARE_PERCENT / 100);
    }

    private static Result pass(boolean passed) {
        return passed ? Result.PASS : Result.FAIL;
    }

    private static List<Double> present(Side side, String metric) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Double> run : side.runs()) {
            Double v = run.get(metric);
            if (v != null) values.add(v);
        }
        return values;
    }

    private static double extreme(Side side, String metric, DoubleBinaryOperator pick) {
        return present(side, metric).stream().mapToDouble(Double::doubleValue).reduce(pick).orElseThrow();
    }

    private static double sum(Side side, String metric) {
        return present(side, metric).stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double median(Side side, String metric) {
        return median(present(side, metric));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String whole(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
