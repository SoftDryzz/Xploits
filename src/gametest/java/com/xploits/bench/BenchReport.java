package com.xploits.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xploits.bench.Metrics.Better;
import com.xploits.bench.Metrics.Definition;
import com.xploits.bench.core.Acceptance;
import com.xploits.bench.core.Acceptance.Outcome;
import com.xploits.bench.core.Acceptance.Verdict;
import com.xploits.bench.core.Recommendation;
import com.xploits.bench.core.RiskTable;
import com.xploits.pvp.crystal.core.RiskLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The bench report (spec {@code 2026-09-25-ingame-bench}, §Report and baseline): {@code
 * report-<version>.json} and an English {@code .md} table, both rewritten after every run so a crash
 * still leaves what ran. For a MEASURE it gives the median, min and max of every metric over its DONE
 * runs, how many runs popped the sparring, the metrics whose runs spread too far (noisy), and the ones
 * worse than the baseline (regressions). A MEASURE judged against another ({@link Scenario#compareWith})
 * also gets that verdict ({@link Acceptance}), and the summary counts the verdicts. One more line per
 * crystal-aura++ risk level gives the strict recommendation over every pair the full bench judges at that
 * level ({@link Recommendation}), and a risk table sets each level's damage dealt and min health beside
 * Meteor's ({@link RiskTable}). Neither a verdict nor a recommendation makes the report fail (crystal-aura++
 * spec §4, Q5: only a run's ERROR does).
 *
 * <p>Numbers, names, statuses and bench-written messages only: never a position. No line of either file
 * holds three numbers in a row separated by spaces or commas (the hygiene scan would take it for a
 * position): numbers stand one per JSON line, and one per table cell in the {@code .md}.
 */
public final class BenchReport {
    public static final int SCHEMA = 1;
    /** A spread over this share of the median makes a metric noisy. */
    static final double NOISY_SPREAD = 0.25;
    /** Worse than the baseline by more than this share of it (or the noise floor, if larger) is a regression. */
    static final double REGRESSION_SHARE = 0.15;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * A scenario's or a run's status. PENDING is a scenario's only: planned, with runs still missing. It
     * is what a report left by a client that stopped mid-bench shows, and it blocks the release.
     */
    public enum Status {
        PASS, FAIL, ERROR, SKIPPED, DONE, PENDING;

        /** Whether it blocks the release. */
        public boolean fails() {
            return this == FAIL || this == ERROR || this == PENDING;
        }
    }

    /** What the comparisons' summary is called: every comparison is crystal-aura++ against crystal-aura. */
    static final String COMPARE_LABEL = "capp";

    /** The value of the report's {@code hygiene} field once the scan found nothing. */
    public static final String HYGIENE_CLEAN = "clean";

    /**
     * One run: its status, the bench-written error when it did not pass, its numbers, and whether our
     * player died in it ({@link PlayerDied}: a crystal-aura++ comparison is then a REJECT).
     */
    public record Run(Status status, String error, Map<String, Double> metrics, boolean died) {
        public Run {
            metrics = Map.copyOf(metrics);
        }

        public Run(Status status, String error, Map<String, Double> metrics) {
            this(status, error, metrics, false);
        }
    }

    /** A metric worse than the baseline by more than the allowed margin. */
    public record Regression(String metric, double baseline, double now) {
    }

    /**
     * A MEASURE's numbers over its DONE runs. {@code poppedRuns} is null for a scenario without a
     * sparring to pop. {@code noisy} and {@code regressions} are only filled once the scenario is DONE.
     */
    public record Aggregate(Map<String, Double> median, Map<String, Double> min, Map<String, Double> max,
                            Integer poppedRuns, List<String> noisy, List<Regression> regressions) {
        static final Aggregate NONE = new Aggregate(Map.of(), Map.of(), Map.of(), null, List.of(), List.of());
    }

    private final String addon;
    private final String meteor;
    private final Path folder;
    private final Baseline baseline;
    /** {@code -Pbench.only}'s names, comma-joined, or null for a full run: so a partial run stays visible
     * in the report and not only in the console line that started it. */
    private final String only;
    /**
     * Every scenario the full bench judges against another, whether it runs in this invocation or not, with
     * the risk level crystal-aura++ runs at in it.
     */
    private final Map<String, RiskLevel> judged;
    /** The planned scenarios, then any other that ran, in order. */
    private final Map<String, Entry> scenarios = new LinkedHashMap<>();
    /**
     * Lines of the report files the hygiene scan took for a position: null until the scan ran, empty when
     * it found none. The report's {@code hygiene} field is absent, {@value #HYGIENE_CLEAN}, or these lines.
     */
    private List<String> hygiene;

    private final class Entry {
        final Scenario scenario;
        final List<Run> runs = new ArrayList<>();

        Entry(Scenario scenario) {
            this.scenario = scenario;
        }

        /**
         * FAIL or ERROR as soon as a run is; otherwise PENDING while runs are missing (a client that stops
         * mid-bench leaves it so); then a CHECK's run status, or DONE for a MEASURE whose runs all are.
         */
        Status status() {
            for (Run run : runs) {
                if (run.status().fails()) return run.status();
            }
            if (runs.size() < scenario.runs()) return Status.PENDING;
            return runs.getFirst().status();
        }

        String error() {
            for (Run run : runs) {
                if (run.status().fails()) return run.error();
            }
            if (!runs.isEmpty() && runs.size() < scenario.runs()) {
                return "incomplete: " + runs.size() + " of " + scenario.runs() + " runs";
            }
            return null;
        }

        /** The verdict against {@link Scenario#compareWith}, judged on this invocation's runs alone. */
        Optional<Outcome> compare() {
            return scenario.compareWith().map(with -> {
                Entry other = scenarios.get(with);
                return Acceptance.judge(side(), other == null ? Acceptance.Side.absent(with) : other.side());
            });
        }

        private Acceptance.Side side() {
            List<Map<String, Double>> done = runs.stream().filter(r -> r.status() == Status.DONE).map(Run::metrics).toList();
            return new Acceptance.Side(scenario.name(), status().name(), done, runs.stream().anyMatch(Run::died));
        }

        Aggregate aggregate() {
            if (scenario.kind() != Scenario.Kind.MEASURE) return Aggregate.NONE;
            List<Run> done = runs.stream().filter(r -> r.status() == Status.DONE).toList();
            boolean complete = status() == Status.DONE;
            return BenchReport.aggregate(done, complete ? baseline.scenario(scenario.name()) : null, complete);
        }
    }

    /** {@code judged}: every scenario the full bench judges, with its level ({@link Scenarios#judged}). */
    public BenchReport(String addon, String meteor, Path folder, Baseline baseline, List<String> only,
                       Map<String, RiskLevel> judged) {
        this.addon = addon;
        this.meteor = meteor;
        this.folder = folder;
        this.baseline = baseline;
        this.only = only.isEmpty() ? null : String.join(",", only);
        this.judged = new LinkedHashMap<>(judged);
    }

    /** Lists the scenarios about to run, PENDING, so a report cut short shows what never ran. */
    public void plan(List<Scenario> planned) {
        for (Scenario scenario : planned) scenarios.computeIfAbsent(scenario.name(), name -> new Entry(scenario));
    }

    public void add(Scenario scenario, Run run) {
        scenarios.computeIfAbsent(scenario.name(), name -> new Entry(scenario)).runs.add(run);
    }

    /** Whether any scenario is FAIL, ERROR or PENDING, or the hygiene scan found a line. */
    public boolean failed() {
        return hygieneHits() || scenarios.values().stream().anyMatch(e -> e.status().fails());
    }

    private boolean hygieneHits() {
        return hygiene != null && !hygiene.isEmpty();
    }

    /** One line for the console and the final assertion: counts per status. */
    public String summary() {
        Map<Status, Integer> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) counts.put(status, 0);
        for (Entry e : scenarios.values()) counts.merge(e.status(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        counts.forEach((status, n) -> {
            if (n > 0) parts.add(n + " " + status);
        });
        int regressions = scenarios.values().stream().mapToInt(e -> e.aggregate().regressions().size()).sum();
        if (regressions > 0) parts.add(regressions + " regression(s)");
        compareSummary().ifPresent(parts::add);
        if (hygieneHits()) parts.add("hygiene ERROR");
        else if (hygiene != null) parts.add("hygiene " + HYGIENE_CLEAN);
        return parts.isEmpty() ? "no scenario ran" : String.join(", ", parts);
    }

    /**
     * The verdicts counted, {@code capp: n ACCEPT / m REJECT / k INCOMPLETE / j NOT_APPLICABLE}; empty when
     * no scenario that ran is judged against another.
     */
    public Optional<String> compareSummary() {
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Verdict verdict : Verdict.values()) counts.put(verdict, 0);
        boolean any = false;
        for (Entry e : scenarios.values()) {
            Optional<Outcome> outcome = e.compare();
            if (outcome.isEmpty()) continue;
            any = true;
            counts.merge(outcome.get().verdict(), 1, Integer::sum);
        }
        if (!any) return Optional.empty();
        List<String> parts = new ArrayList<>();
        counts.forEach((verdict, n) -> parts.add(n + " " + verdict.name()));
        return Optional.of(COMPARE_LABEL + ": " + String.join(" / ", parts));
    }

    /**
     * Whether crystal-aura++ is recommended at each risk level (crystal-aura++ spec, Round 2, strict
     * criterion; R2-5), each over every pair the full bench judges at that level: a pair that did not run in
     * this invocation is INCOMPLETE, so only a full run can say YES. Empty when no scenario that ran is judged
     * against another.
     */
    public List<Recommendation> recommendations() {
        if (compareSummary().isEmpty()) return List.of();
        List<Recommendation.Judged> pairs = new ArrayList<>();
        judged.forEach((name, level) -> {
            Entry e = scenarios.get(name);
            Optional<Outcome> outcome = e == null ? Optional.empty() : e.compare();
            pairs.add(new Recommendation.Judged(level, outcome.map(Outcome::verdict).orElse(Verdict.INCOMPLETE)));
        });
        return Recommendation.byLevel(pairs);
    }

    /**
     * The risk table's rows: each Meteor scenario a crystal-aura++ scenario of this report is judged against,
     * in the report's order, with the medians of Meteor's side and of each level's.
     */
    private List<RiskTable.Row> riskRows() {
        Map<String, Map<RiskLevel, Map<String, Double>>> byMeteor = new LinkedHashMap<>();
        for (Entry e : scenarios.values()) {
            Optional<String> with = e.scenario.compareWith();
            Optional<RiskLevel> level = e.scenario.risk();
            if (with.isEmpty() || level.isEmpty()) continue;
            byMeteor.computeIfAbsent(with.get(), name -> new EnumMap<>(RiskLevel.class)).put(level.get(), e.aggregate().median());
        }
        // Meteor's scenarios in the report's order, then any that is not in this report.
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String name : scenarios.keySet()) {
            if (byMeteor.containsKey(name)) order.add(name);
        }
        order.addAll(byMeteor.keySet());
        List<RiskTable.Row> rows = new ArrayList<>();
        for (String name : order) {
            Entry meteorSide = scenarios.get(name);
            rows.add(new RiskTable.Row(name, meteorSide == null ? Map.of() : meteorSide.aggregate().median(), byMeteor.get(name)));
        }
        return rows;
    }

    /**
     * The hygiene scan's result: the lines it found ({@code <file> line <n>}), which make the bench fail,
     * or none, which the report then states as {@value #HYGIENE_CLEAN}.
     */
    public void hygiene(List<String> hits) {
        hygiene = List.copyOf(hits);
    }

    /**
     * Puts every DONE MEASURE's medians into {@code target} (spec: {@code -Pbench.updateBaseline}); a
     * scenario that is not DONE, and every scenario that did not run, keeps its entry. Returns how many
     * scenarios were put.
     */
    public int updateBaseline(Baseline target) {
        int put = 0;
        for (Entry e : scenarios.values()) {
            if (e.scenario.kind() == Scenario.Kind.MEASURE && e.status() == Status.DONE) {
                target.put(e.scenario.name(), e.aggregate().median());
                put++;
            }
        }
        return put;
    }

    public Path jsonFile() {
        return folder.resolve("report-" + addon + ".json");
    }

    public Path markdownFile() {
        return folder.resolve("report-" + addon + ".md");
    }

    public void write() throws IOException {
        Files.createDirectories(folder);
        Files.writeString(jsonFile(), GSON.toJson(json()) + "\n", StandardCharsets.UTF_8);
        Files.writeString(markdownFile(), markdown(), StandardCharsets.UTF_8);
    }

    // --- Numbers ---------------------------------------------------------------------------------

    /**
     * The median, min and max of each metric over {@code done} (in the metric table's order), the popped
     * runs, and, when {@code complete}, the noisy metrics and the regressions against {@code base} (null:
     * no comparison).
     */
    static Aggregate aggregate(List<Run> done, Map<String, Double> base, boolean complete) {
        Map<String, Double> median = new LinkedHashMap<>();
        Map<String, Double> min = new LinkedHashMap<>();
        Map<String, Double> max = new LinkedHashMap<>();
        List<String> noisy = new ArrayList<>();
        List<Regression> regressions = new ArrayList<>();
        for (Definition metric : Metrics.TABLE) {
            List<Double> values = done.stream().map(r -> r.metrics().get(metric.name())).filter(v -> v != null).sorted().toList();
            if (values.isEmpty()) continue;
            if (metric.perScenario()) {
                double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                median.put(metric.name(), sum);
                min.put(metric.name(), sum);
                max.put(metric.name(), sum);
            } else {
                median.put(metric.name(), median(values));
                min.put(metric.name(), values.getFirst());
                max.put(metric.name(), values.getLast());
            }
            if (!complete) continue;
            double m = median.get(metric.name());
            if (!metric.perScenario() && max.get(metric.name()) - min.get(metric.name()) > NOISY_SPREAD * Math.abs(m)
                && Math.abs(m) > metric.floor()) {
                noisy.add(metric.name());
            }
            Double was = base == null ? null : base.get(metric.name());
            if (was != null && worse(metric, was, m)) regressions.add(new Regression(metric.name(), was, m));
        }
        Integer popped = null;
        if (done.stream().anyMatch(r -> r.metrics().containsKey(Metrics.SPARRING_POPS))) {
            popped = (int) done.stream().filter(r -> r.metrics().getOrDefault(Metrics.SPARRING_POPS, 0.0) > 0).count();
        }
        return new Aggregate(median, min, max, popped, noisy, regressions);
    }

    /** Worse than {@code baseline} by more than {@code max(15 % of it, the noise floor)}. */
    static boolean worse(Definition metric, double baseline, double now) {
        double margin = Math.max(REGRESSION_SHARE * Math.abs(baseline), metric.floor());
        return metric.better() == Better.HIGHER ? now < baseline - margin : now > baseline + margin;
    }

    static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** Three decimals: what the report and the baseline keep. */
    static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    // --- JSON ------------------------------------------------------------------------------------

    private JsonObject json() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("addon", addon);
        root.addProperty("meteor", meteor);
        root.addProperty("difficulty", "normal");
        root.addProperty("only", only);
        JsonArray list = new JsonArray();
        for (Entry e : scenarios.values()) {
            JsonObject s = new JsonObject();
            s.addProperty("name", e.scenario.name());
            s.addProperty("kind", e.scenario.kind().name());
            s.addProperty("status", e.status().name());
            String error = e.error();
            if (error != null) s.addProperty("error", error);
            JsonArray runs = new JsonArray();
            for (Run run : e.runs) runs.add(numbers(run.metrics()));
            s.add("runs", runs);
            Aggregate aggregate = e.aggregate();
            s.add("median", numbers(aggregate.median()));
            s.add("min", numbers(aggregate.min()));
            s.add("max", numbers(aggregate.max()));
            if (aggregate.poppedRuns() != null) s.addProperty("popped_runs", aggregate.poppedRuns());
            JsonArray noisy = new JsonArray();
            aggregate.noisy().forEach(noisy::add);
            s.add("noisy", noisy);
            JsonArray regressions = new JsonArray();
            for (Regression r : aggregate.regressions()) {
                JsonObject o = new JsonObject();
                o.addProperty("metric", r.metric());
                o.addProperty("baseline", round(r.baseline()));
                o.addProperty("now", round(r.now()));
                regressions.add(o);
            }
            s.add("regressions", regressions);
            e.compare().ifPresent(outcome -> s.add("compare", compare(e.scenario.compareWith().orElseThrow(), outcome)));
            list.add(s);
        }
        root.add("scenarios", list);
        compareSummary().ifPresent(summary -> root.addProperty("compare", summary));
        List<Recommendation> recommendations = recommendations();
        if (!recommendations.isEmpty()) {
            JsonArray lines = new JsonArray();
            recommendations.forEach(r -> lines.add(r.line()));
            root.add("recommendation", lines);
        }
        // Absent until the scan ran: the Gradle-side check (benchVerify) wants it, and wants it clean.
        if (hygieneHits()) {
            JsonArray lines = new JsonArray();
            hygiene.forEach(lines::add);
            root.add("hygiene", lines);
        } else if (hygiene != null) {
            root.addProperty("hygiene", HYGIENE_CLEAN);
        }
        return root;
    }

    /** A verdict: against what, the verdict, why when it is not ACCEPT, and each rule with what it compared. */
    private static JsonObject compare(String with, Outcome outcome) {
        JsonObject o = new JsonObject();
        o.addProperty("with", with);
        o.addProperty("verdict", outcome.verdict().name());
        if (outcome.reason() != null) o.addProperty("reason", outcome.reason());
        JsonArray rules = new JsonArray();
        for (Acceptance.Rule rule : outcome.rules()) {
            JsonObject r = new JsonObject();
            r.addProperty("rule", rule.id());
            r.addProperty("kind", rule.kind().name().toLowerCase(Locale.ROOT));
            r.addProperty("result", rule.result().name());
            r.addProperty("detail", rule.detail());
            rules.add(r);
        }
        o.add("rules", rules);
        return o;
    }

    /** Metrics in the table's order, rounded; one per line once pretty-printed. */
    private static JsonObject numbers(Map<String, Double> values) {
        JsonObject o = new JsonObject();
        for (Definition metric : Metrics.TABLE) {
            Double v = values.get(metric.name());
            if (v != null) o.addProperty(metric.name(), round(v));
        }
        return o;
    }

    // --- Markdown --------------------------------------------------------------------------------

    private String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Bench report ").append(addon).append("\n\n");
        md.append("Xploits ").append(addon).append(", Meteor ").append(meteor).append(", difficulty normal. ")
            .append(summary()).append(".\n\n");
        for (Recommendation r : recommendations()) md.append(r.line()).append(".\n\n");
        md.append(baseline.size() == 0 ? "No baseline to compare with.\n\n"
            : "Compared with the baseline in bench/baseline.json.\n\n");
        md.append("| Scenario | Kind | Runs | Status | Error |\n");
        md.append("|---|---|---|---|---|\n");
        for (Entry e : scenarios.values()) {
            String error = e.error();
            md.append("| ").append(e.scenario.name())
                .append(" | ").append(e.scenario.kind().name())
                .append(" | ").append(e.runs.size()).append(" of ").append(e.scenario.runs())
                .append(" | ").append(e.status().name())
                .append(" | ").append(error == null ? "" : cell(error))
                .append(" |\n");
        }
        for (Entry e : scenarios.values()) {
            // A MEASURE with no runs yet (PENDING) has nothing to tabulate: skip the table instead of
            // printing a header with no rows under it.
            if (e.scenario.kind() == Scenario.Kind.MEASURE && !e.runs.isEmpty()) measure(md, e);
        }
        comparisons(md);
        riskTable(md);
        if (hygieneHits()) {
            md.append("\n## Hygiene\n\nThese lines look like a position:\n\n");
            for (String hit : hygiene) md.append("- ").append(hit).append('\n');
        }
        return md.toString();
    }

    /** One MEASURE's table: a row per metric, one number per cell. */
    private void measure(StringBuilder md, Entry e) {
        Aggregate aggregate = e.aggregate();
        Map<String, Double> base = baseline.scenario(e.scenario.name());
        md.append("\n## ").append(e.scenario.name()).append(" (").append(e.status().name()).append(")\n\n");
        if (aggregate.poppedRuns() != null) {
            long done = e.runs.stream().filter(r -> r.status() == Status.DONE).count();
            md.append("The sparring popped in ").append(aggregate.poppedRuns()).append(" of ").append(done)
                .append(" DONE runs; first_pop_s is the median of those runs only, and is not compared")
                .append(" when no run popped (no_pop_runs carries it).\n\n");
        }
        md.append("| Metric | Better | Median | Min | Max");
        for (int i = 1; i <= e.scenario.runs(); i++) md.append(" | Run ").append(i);
        md.append(" | Baseline | Flags |\n|---|---|---|---|---");
        for (int i = 1; i <= e.scenario.runs(); i++) md.append("|---");
        md.append("|---|---|\n");
        for (Definition metric : Metrics.TABLE) {
            String name = metric.name();
            if (!aggregate.median().containsKey(name) && e.runs.stream().noneMatch(r -> r.metrics().containsKey(name))) continue;
            md.append("| ").append(name)
                .append(" | ").append(metric.better().name().toLowerCase(Locale.ROOT))
                .append(" | ").append(number(aggregate.median().get(name)))
                .append(" | ").append(number(aggregate.min().get(name)))
                .append(" | ").append(number(aggregate.max().get(name)));
            for (int i = 0; i < e.scenario.runs(); i++) {
                String cell;
                if (i >= e.runs.size()) cell = "";
                else if (e.runs.get(i).status() != Status.DONE) cell = e.runs.get(i).status().name();
                else cell = number(e.runs.get(i).metrics().get(name));
                md.append(" | ").append(cell);
            }
            List<String> flags = new ArrayList<>();
            if (aggregate.noisy().contains(name)) flags.add("noisy");
            if (aggregate.regressions().stream().anyMatch(r -> r.metric().equals(name))) flags.add("REGRESSION");
            md.append(" | ").append(number(base == null ? null : base.get(name)))
                .append(" | ").append(String.join(" and ", flags))
                .append(" |\n");
        }
    }

    /** Every verdict: a line with it and why, then a row per rule. */
    private void comparisons(StringBuilder md) {
        boolean header = false;
        for (Entry e : scenarios.values()) {
            Optional<Outcome> judged = e.compare();
            if (judged.isEmpty()) continue;
            Outcome outcome = judged.get();
            if (!header) {
                md.append("\n## crystal-aura++ against crystal-aura\n\n")
                    .append("A verdict never fails the bench; a crystal-aura++ run in which our player died is ERROR")
                    .append(" and makes the verdict REJECT. A pair where neither aura placed a crystal in any run is")
                    .append(" NOT_APPLICABLE: it is not evidence either way. Each risk level gets its own recommendation:")
                    .append(" crystal-aura++ is recommended at a level only when every applicable pair of the full bench")
                    .append(" at that level, the -regen ones with healing included, is ACCEPT; a pair that did not run in")
                    .append(" this invocation counts as INCOMPLETE. The capp-X pairs run at Safe, the default; the")
                    .append(" capp-balanced-X and capp-aggressive-X pairs at those levels.\n");
                header = true;
            }
            md.append("\n### ").append(e.scenario.name()).append(" against ").append(e.scenario.compareWith().orElseThrow())
                .append(": ").append(outcome.verdict().name()).append("\n\n");
            if (outcome.reason() != null) md.append("Why: ").append(outcome.reason()).append(".\n\n");
            if (outcome.rules().isEmpty()) continue;
            md.append("| Rule | Kind | Result | What was compared |\n|---|---|---|---|\n");
            for (Acceptance.Rule rule : outcome.rules()) {
                md.append("| ").append(rule.id())
                    .append(" | ").append(rule.kind().name().toLowerCase(Locale.ROOT))
                    .append(" | ").append(rule.result().name())
                    .append(" | ").append(cell(rule.detail()))
                    .append(" |\n");
            }
        }
    }

    /** The risk table: damage dealt and min health, Meteor's and each level's, per Meteor scenario. */
    private void riskTable(StringBuilder md) {
        List<String> lines = RiskTable.lines(riskRows());
        if (lines.isEmpty()) return;
        md.append("\n## crystal-aura++ risk levels\n\n")
            .append("Medians over each scenario's DONE runs: Meteor's crystal-aura, then crystal-aura++ at each level")
            .append(" (Safe is the default); a dash is a side that did not run.\n\n");
        for (String line : lines) md.append(line).append('\n');
    }

    private static String number(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
