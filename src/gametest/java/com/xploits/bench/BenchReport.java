package com.xploits.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xploits.bench.Metrics.Better;
import com.xploits.bench.Metrics.Definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bench report (spec {@code 2026-09-25-ingame-bench}, §Report and baseline): {@code
 * report-<version>.json} and an English {@code .md} table, both rewritten after every run so a crash
 * still leaves what ran. For a MEASURE it gives the median, min and max of every metric over its DONE
 * runs, how many runs popped the sparring, the metrics whose runs spread too far (noisy), and the ones
 * worse than the baseline (regressions).
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

    public enum Status {
        PASS, FAIL, ERROR, SKIPPED, DONE;

        /** Whether it blocks the release. */
        public boolean fails() {
            return this == FAIL || this == ERROR;
        }
    }

    /** One run: its status, the bench-written error when it did not pass, and its numbers. */
    public record Run(Status status, String error, Map<String, Double> metrics) {
        public Run {
            metrics = Map.copyOf(metrics);
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
    /** The scenarios with at least one run, in the order they ran. */
    private final Map<String, Entry> scenarios = new LinkedHashMap<>();
    /** Lines of the report files the hygiene scan took for a position; empty until a scan finds one. */
    private final List<String> hygiene = new ArrayList<>();

    private final class Entry {
        final Scenario scenario;
        final List<Run> runs = new ArrayList<>();

        Entry(Scenario scenario) {
            this.scenario = scenario;
        }

        /**
         * A CHECK: its run's status. A MEASURE: DONE once all its runs are DONE; ERROR if any run is, or
         * while runs are missing (a crash leaves it so).
         */
        Status status() {
            for (Run run : runs) {
                if (run.status().fails()) return run.status();
            }
            if (runs.size() < scenario.runs()) return Status.ERROR;
            return runs.getFirst().status();
        }

        String error() {
            for (Run run : runs) {
                if (run.status().fails()) return run.error();
            }
            if (runs.size() < scenario.runs()) return "incomplete: " + runs.size() + " of " + scenario.runs() + " runs";
            return null;
        }

        Aggregate aggregate() {
            if (scenario.kind() != Scenario.Kind.MEASURE) return Aggregate.NONE;
            List<Run> done = runs.stream().filter(r -> r.status() == Status.DONE).toList();
            boolean complete = status() == Status.DONE;
            return BenchReport.aggregate(done, complete ? baseline.scenario(scenario.name()) : null, complete);
        }
    }

    public BenchReport(String addon, String meteor, Path folder, Baseline baseline) {
        this.addon = addon;
        this.meteor = meteor;
        this.folder = folder;
        this.baseline = baseline;
    }

    public void add(Scenario scenario, Run run) {
        scenarios.computeIfAbsent(scenario.name(), name -> new Entry(scenario)).runs.add(run);
    }

    /** Whether any scenario is FAIL or ERROR, or the hygiene scan found a line. */
    public boolean failed() {
        return !hygiene.isEmpty() || scenarios.values().stream().anyMatch(e -> e.status().fails());
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
        if (!hygiene.isEmpty()) parts.add("hygiene ERROR");
        return parts.isEmpty() ? "no scenario ran" : String.join(", ", parts);
    }

    /** The lines the hygiene scan found ({@code <file> line <n>}); they make the bench fail. */
    public void hygiene(List<String> hits) {
        hygiene.addAll(hits);
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
            list.add(s);
        }
        root.add("scenarios", list);
        if (!hygiene.isEmpty()) {
            JsonArray lines = new JsonArray();
            hygiene.forEach(lines::add);
            root.add("hygiene", lines);
        }
        return root;
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
            if (e.scenario.kind() == Scenario.Kind.MEASURE) measure(md, e);
        }
        if (!hygiene.isEmpty()) {
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
                .append(" DONE runs; first_pop_s is the median of those runs only.\n\n");
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

    private static String number(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
