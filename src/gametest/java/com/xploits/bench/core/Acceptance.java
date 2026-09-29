package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.RiskLevel;
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
 *   <tr><td>S3</td><td>not applicable when Meteor never went below the ++ scenario's risk level's reserve
 *   (round 3: nothing there for the budget to prevent); otherwise, only when Meteor's {@code self_damage}
 *   &ge; 2: ++'s &le; Meteor's &minus; max(1, 15 %), or ++'s {@code min_health} &ge; Meteor's + 1</td>
 *   <td>medians, and Meteor's lowest run</td></tr>
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
 * <p>Task A3: a pair of the fight scenarios (detected from the runs themselves, whichever side has any —
 * only a fight's runs ever carry {@code result}) is judged by three rules instead, over the same {@value
 * #RUNS} DONE runs a side needs:
 * <table>
 *   <caption>Fight acceptance rules</caption>
 *   <tr><th>Rule</th><th>What must hold</th><th>Over</th></tr>
 *   <tr><td>F1</td><td>{@code result} (++ must not lose where Meteor wins): ++'s median &ge; Meteor's
 *   &mdash; win 1 &gt; draw 0 &gt; loss -1, no margin (one of three exact values, not a continuous
 *   quantity)</td><td>medians</td></tr>
 *   <tr><td>F2</td><td>{@code net_pops} &ge; Meteor's &minus; 1</td><td>medians</td></tr>
 *   <tr><td>F3</td><td>every ++ run's {@code min_health_after_own_hit} &ge; the risk level's own reserve
 *   ({@code after == 0}, {@code MinHealthAfterOwnHit}'s own lethal-hit convention, always fails this against
 *   any real reserve); NOT_APPLICABLE without a fixed level, or when no run took a hit from its own
 *   crystal</td><td>the lowest of every run's value</td></tr>
 * </table>
 * <table><caption>F4</caption><tr><td>F4</td><td>(task B0b, safety) no run of ours ends in our death while we carried
 * a totem before the hit; {@code totems_at_finishing_hit_min} &ge; 2; {@code min_health_after_finishing_hit},
 * when present, &gt; 0</td><td>every run</td></tr></table>
 * Task C2 (M5): S4 and F4 also require zero pop-grade finishing hits ({@code finishing_pop_hits}) at Safe, Balanced and
 * Custom; only at Aggressive are they allowed, and then the floor and pop checks judge them.
 * The same before-the-rules order applies (death, gaps, never-placed), and {@link Recommendation}'s levels
 * already fold a fight pair in like any other, since it reads verdicts, not which rules produced them.
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

    // Task A3: the fight scenarios' own metric names (FightMeasureRun), distinct from the metrics above.
    /** 1 win (the sparring died first), -1 loss (we died), 0 draw (the time limit). */
    public static final String RESULT = "result";
    /** {@code pops_dealt - pops_taken}. */
    public static final String NET_POPS = "net_pops";
    /**
     * The lowest health plus absorption right after a hit from one of our own crystals, over the run; absent
     * in a run where we took none. {@code 0} is DamageLedger's pre-existing lethal-hit convention (a SELF hit
     * that pops or kills us), never a real low-but-surviving value — see {@code MinHealthAfterOwnHit}'s Javadoc.
     */
    public static final String MIN_HEALTH_AFTER_OWN_HIT = "min_health_after_own_hit";
    // Task B0b: the finishing blow's metrics, each only in a run where it happened.
    /** Override crystals (crystal-aura++'s finishing blow) that exploded this run. */
    public static final String FINISHING_BLOWS = "finishing_blows";
    /** The lowest health plus absorption after a finishing hit; above 0 means we were still alive. */
    public static final String MIN_HEALTH_AFTER_FINISHING_HIT = "min_health_after_finishing_hit";
    /** The fewest totems we carried at any finishing hit. */
    public static final String TOTEMS_AT_FINISHING_HIT_MIN = "totems_at_finishing_hit_min";
    /** 1 in a run that ended in our death after we had carried at least one totem before the hit. */
    public static final String DIED_WITH_TOTEM = "died_with_totem";
    /** Finishing blows that cost us a totem. */
    public static final String FINISHING_POPS = "finishing_pops";
    /** Finishing blows against a target holding a totem (pop-grade) that left us below 2 or popped us. */
    public static final String FINISHING_POP_GRADE_VIOLATIONS = "finishing_pop_grade_violations";
    /** Finishing hits on us by a crystal marked pop-grade (the target holds a totem). */
    public static final String FINISHING_POP_HITS = "finishing_pop_hits";
    /** A pop-grade finishing blow (the target holds a totem) never leaves us below this. */
    public static final double POP_GRADE_FLOOR = 2.0;
    /** F4: the finishing blow never happens with fewer than this many totems carried (never the last one). */
    public static final int FINISHING_MIN_TOTEMS = 2;

    /**
     * Every metric a fight pair's judgement requires present in each run ({@link #RESULT}, {@link #NET_POPS});
     * {@link #PLACEMENTS_PER_S} still decides whether the pair applies at all, and
     * {@link #MIN_HEALTH_AFTER_OWN_HIT} is only in a run that took a hit from its own crystal, so neither is
     * required here (the same way {@link #FIRST_POP_S} is not required in {@link #METRICS}).
     */
    public static final List<String> FIGHT_METRICS = List.of(RESULT, NET_POPS, DAMAGE_DEALT, SELF_DAMAGE, MIN_HEALTH,
        PLACEMENTS_PER_S);

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
    /** F2: the {@code net_pops} noise floor. */
    public static final double NET_POPS_MARGIN = 1.0;

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

    /** Judges without a risk level (round 3's S3 exception never applies): kept for a capp scenario that,
     * against expectations, has none. */
    public static Outcome judge(Side capp, Side meteor) {
        return judge(capp, meteor, null);
    }

    /**
     * @param level the ++ scenario's risk level (R3-10's S3 exception reads its reserve), or null when it
     *              has none
     */
    public static Outcome judge(Side capp, Side meteor, RiskLevel level) {
        if (capp.died()) return new Outcome(Verdict.REJECT, "our player died in a " + capp.scenario() + " run", List.of());
        // Task A3: a fight pair (FightMeasureRun's own metrics: result, net_pops, ...) is judged by F1-F3
        // instead of the S/O rules above, which the old scenarios keep exactly as before. Detected from the
        // runs themselves (whichever side has any) rather than from a parameter, so this stays the one entry
        // point BenchReport already calls for every compared pair.
        boolean fight = isFight(capp, meteor);
        List<String> metrics = fight ? FIGHT_METRICS : METRICS;
        for (Side side : List.of(capp, meteor)) {
            String gap = gap(side, metrics);
            if (gap != null) return new Outcome(Verdict.INCOMPLETE, gap, List.of());
        }
        if (neverPlaced(capp) && neverPlaced(meteor)) {
            return new Outcome(Verdict.NOT_APPLICABLE,
                "neither " + capp.scenario() + " nor " + meteor.scenario() + " placed a crystal in any run", List.of());
        }
        List<Rule> rules = fight
            ? List.of(f1(capp, meteor), f2(capp, meteor), f3(capp, level), f4(capp, level))
            : List.of(s1(capp, meteor), s2(capp, meteor), s3(capp, meteor, level), s4(capp, level), o1(capp, meteor),
                o2(capp, meteor), o3(capp, meteor), o4(capp, meteor));
        List<String> failed = rules.stream().filter(r -> r.result() == Result.FAIL).map(Rule::id).toList();
        if (failed.isEmpty()) return new Outcome(Verdict.ACCEPT, null, rules);
        return new Outcome(Verdict.REJECT, "failed: " + String.join(", ", failed), rules);
    }

    /**
     * Whether this pair is a fight scenario (task A3): read from whichever side has a run, since only a
     * fight's runs ever carry {@link #RESULT}. Both sides of a judged pair are always the same kind (a
     * {@code ca-<fight>}/{@code capp-<fight>} pair, or an old-style pair, never a mix), so the first run found
     * on either side settles it; when neither side ran at all the kind is moot, since {@link #gap} then
     * returns "did not run" before either metric list is ever consulted.
     */
    private static boolean isFight(Side capp, Side meteor) {
        for (Side side : List.of(capp, meteor)) {
            if (!side.runs().isEmpty()) return side.runs().getFirst().containsKey(RESULT);
        }
        return false;
    }

    /** Why this side cannot be judged, or null when it can. */
    private static String gap(Side side, List<String> metrics) {
        if (side.status() == null) return side.scenario() + " did not run in this invocation";
        if (!DONE.equals(side.status())) return side.scenario() + " is " + side.status();
        if (side.runs().size() != RUNS) return side.scenario() + " has " + side.runs().size() + " DONE runs, not " + RUNS;
        for (Map<String, Double> run : side.runs()) {
            for (String metric : metrics) {
                // FIRST_POP_S (old scenarios) and MIN_HEALTH_AFTER_OWN_HIT (fight scenarios) are each only
                // ever in one metric list, but the check is unconditional so neither list has to remember to
                // repeat it: both are optional per run (absent when nothing to report), never a gap.
                if (metric.equals(FIRST_POP_S) || metric.equals(MIN_HEALTH_AFTER_OWN_HIT)) continue;
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

    private static Rule s3(Side capp, Side meteor, RiskLevel level) {
        // CUSTOM has no fixed reserve to compare Meteor's lowest run against (its own is a player setting
        // this pure core never sees); keep today's S3 for it, as for no level at all.
        if (level != null && level != RiskLevel.CUSTOM) {
            double reserve = level.reserve(Double.NaN);
            double meteorLow = extreme(meteor, MIN_HEALTH, Math::min);
            if (meteorLow >= reserve) {
                return new Rule("S3", Kind.SAFETY, Result.NOT_APPLICABLE,
                    "Meteor never went below the reserve " + n(reserve) + " (lowest " + n(meteorLow) + "): nothing to make safer");
            }
        }
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

    // --- Task A3: the fight rules (F1 result, F2 net_pops, F3 the reserve) ---------------------------

    /** F1: {@code result}, median, ++ must not lose where Meteor wins — read generally as "never worse"
     * (win 1 &gt; draw 0 &gt; loss -1), the order the categorical value's own definition gives it. No margin:
     * it is one of three exact values, not a continuous quantity. */
    private static Rule f1(Side capp, Side meteor) {
        double ours = median(capp, RESULT);
        double theirs = median(meteor, RESULT);
        return new Rule("F1", Kind.OFFENSE, pass(ours >= theirs),
            "median " + RESULT + ": ++ " + n(ours) + ", Meteor " + n(theirs) + ", needs >= " + n(theirs));
    }

    /** F2: {@code net_pops}, median, not worse than Meteor's beyond the noise margin. */
    private static Rule f2(Side capp, Side meteor) {
        double theirs = median(meteor, NET_POPS);
        return atLeast("F2", capp, NET_POPS, theirs, theirs - NET_POPS_MARGIN);
    }

    /**
     * F3 (safety): every ++ run's {@code min_health_after_own_hit} must be at least the level's own reserve —
     * our own crystals must never take us below what the level promises, whatever the opponent does to us
     * meanwhile. {@code 0} (a lethal own hit, {@code MinHealthAfterOwnHit}'s own convention) always fails this
     * against any real reserve. NOT_APPLICABLE without a fixed level (no level at all, or CUSTOM, a player
     * setting this pure core never sees — the same exception {@link #s3} makes), and when no run of the side
     * ever took a hit from its own crystal (nothing here for the reserve to have protected).
     */
    private static Rule f3(Side capp, RiskLevel level) {
        if (level == null || level == RiskLevel.CUSTOM) {
            return new Rule("F3", Kind.SAFETY, Result.NOT_APPLICABLE,
                "no fixed reserve to check for " + (level == null ? "no risk level" : level));
        }
        List<Double> values = present(capp, MIN_HEALTH_AFTER_OWN_HIT);
        if (values.isEmpty()) {
            return new Rule("F3", Kind.SAFETY, Result.NOT_APPLICABLE,
                "no run of " + capp.scenario() + " took a hit from its own crystal");
        }
        double reserve = level.reserve(Double.NaN);
        double lowest = values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        return new Rule("F3", Kind.SAFETY, pass(lowest >= reserve),
            "lowest run " + MIN_HEALTH_AFTER_OWN_HIT + ": ++ " + n(lowest) + ", needs >= " + n(reserve) + " (" + level + ")");
    }

    /**
     * F4 (safety, task B0b): the finishing blow keeps its promises, over every ++ run. We never end a run dead
     * while we carried a totem before the hit ({@link #DIED_WITH_TOTEM}); no finishing hit happens with fewer
     * than {@value #FINISHING_MIN_TOTEMS} totems carried (never the last one); and after a finishing hit we
     * were still alive ({@link #MIN_HEALTH_AFTER_FINISHING_HIT} above 0: the totem saved us, or the hit was not
     * lethal). The two finishing metrics are absent in a run without a finishing hit, which is nothing to
     * judge, never a failure. Unlike F3 it needs no fixed level for its checks: the finishing blow applies at every
     * level; only the pop-grade allowance depends on it (task C2, M5): zero pop-grade finishing hits at Safe, Balanced,
     * Custom and without a level; at Aggressive they are judged by the floor and pop checks alone.
     */
    private static Rule f4(Side capp, RiskLevel level) {
        double deaths = sum(capp, DIED_WITH_TOTEM);
        List<Double> totems = present(capp, TOTEMS_AT_FINISHING_HIT_MIN);
        List<Double> after = present(capp, MIN_HEALTH_AFTER_FINISHING_HIT);
        double fewest = totems.isEmpty() ? Double.NaN : totems.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double lowest = after.isEmpty() ? Double.NaN : after.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double violations = sum(capp, FINISHING_POP_GRADE_VIOLATIONS);
        double popHits = popHitsNotAllowed(capp, level);
        boolean passed = deaths == 0 && violations == 0 && popHits == 0 && (totems.isEmpty() || fewest >= FINISHING_MIN_TOTEMS)
            && (after.isEmpty() || lowest > 0);
        return new Rule("F4", Kind.SAFETY, pass(passed),
            "runs dead while carrying a totem: " + whole(deaths) + ", needs 0; fewest totems at a finishing hit: "
                + (totems.isEmpty() ? "none" : n(fewest)) + ", needs >= " + FINISHING_MIN_TOTEMS
                + "; lowest health after a finishing hit: " + (after.isEmpty() ? "none" : n(lowest)) + ", needs > 0"
                + "; pop-grade blows (target holding a totem) that left us below " + n(POP_GRADE_FLOOR) + " or popped us: "
                + whole(violations) + ", needs 0; pop-grade finishing hits: " + popHitsText(capp, level));
    }

    /**
     * S4 (safety, task B0b/B0c): in the older scenarios the target always holds a totem, so every finishing blow
     * there is pop-grade: it may take us below the level's reserve, which S1-S3 do not judge (they read ordinary
     * own hits only), but never below {@value #POP_GRADE_FLOOR} health plus absorption, and never pops us. Over
     * every run of ours; the metrics are absent in a run without a finishing blow, which is nothing to judge.
     */
    private static Rule s4(Side capp, RiskLevel level) {
        List<Double> after = present(capp, MIN_HEALTH_AFTER_FINISHING_HIT);
        double lowest = after.isEmpty() ? Double.NaN : after.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double pops = sum(capp, FINISHING_POPS);
        List<Double> totems = present(capp, TOTEMS_AT_FINISHING_HIT_MIN);
        double fewest = totems.isEmpty() ? Double.NaN : totems.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double popHits = popHitsNotAllowed(capp, level);
        boolean passed = pops == 0 && popHits == 0 && (after.isEmpty() || lowest >= POP_GRADE_FLOOR)
            && (totems.isEmpty() || fewest >= FINISHING_MIN_TOTEMS);
        return new Rule("S4", Kind.SAFETY, pass(passed),
            "finishing blows that popped us: " + whole(pops) + ", needs 0; lowest health after a finishing hit: "
                + (after.isEmpty() ? "none" : n(lowest)) + ", needs >= " + n(POP_GRADE_FLOOR)
                + "; fewest totems at a finishing hit: " + (totems.isEmpty() ? "none" : n(fewest)) + ", needs >= "
                + FINISHING_MIN_TOTEMS + "; pop-grade finishing hits: " + popHitsText(capp, level));
    }

    /**
     * Task C2 (M5): pop-grade finishing hits (a crystal marked pop-grade hurt us) are only allowed at Aggressive,
     * whose reserve equals the floor; at Safe, Balanced, Custom, and without a level, any is a failure. Returns
     * the count that is not allowed. At Aggressive the floor and pop checks above still judge them.
     */
    private static double popHitsNotAllowed(Side capp, RiskLevel level) {
        return level == RiskLevel.AGGRESSIVE ? 0 : sum(capp, FINISHING_POP_HITS);
    }

    private static String popHitsText(Side capp, RiskLevel level) {
        return level == RiskLevel.AGGRESSIVE ? "allowed at Aggressive (the floor and pop checks judge them)"
            : whole(sum(capp, FINISHING_POP_HITS)) + ", needs 0 at " + (level == null ? "no level" : level);
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

    /** The middle value, or the mean of the two middle ones. */
    static double median(List<Double> values) {
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
