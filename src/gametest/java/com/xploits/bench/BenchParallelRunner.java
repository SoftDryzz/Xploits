package com.xploits.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xploits.bench.core.ReportMerge;
import com.xploits.bench.core.RiskTable;
import com.xploits.pvp.crystal.core.RiskLevel;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Task A5: the small, game-free tool the {@code benchMerge} Gradle task runs (a plain {@code main}, no
 * Fabric/Meteor/Minecraft on its own — only what {@link ReportMerge} and {@link Hygiene} already need) to
 * turn {@code n} shards' own {@code report-<version>.json} files into one, and write it into the current
 * worktree's {@code build/bench}. The actual merge (which scenarios, in what order, the {@code compare} and
 * {@code recommendation} lines) is all {@link ReportMerge}'s, in {@code bench/core}; this class only reads the
 * files, calls it, writes what it returns, and — the one thing {@link ReportMerge} deliberately leaves out,
 * since it never touches a file — scans the result for hygiene the same two-pass way {@link BenchTest#hygiene}
 * does, and writes it again with that field filled in.
 *
 * <p>{@code benchVerify} and the release gate ({@code ReleaseGate}) then run on the merged {@code
 * report-<version>.json} completely unchanged: they only ever read that one file, never knowing whether it
 * came from one client or was merged from several.
 */
public final class BenchParallelRunner {
    // serializeNulls(): a plain run's report always has an explicit "only": null (JsonObject.addProperty adds
    // it), never an absent key; without this, Gson's default Map serialization drops a null-valued entry
    // instead of writing it, which would make an unsharded run's report ("only" set) and a merged one
    // ("only" copied straight from a shard, null the same way) differ in shape for no reason.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private BenchParallelRunner() {
    }

    /**
     * @param args {@code <version> <outFolder> <baselineFile|-> <true|false: -Pbench.updateBaseline>
     *             <shardReport1.json> [shardReport2.json ...]}
     */
    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("usage: BenchParallelRunner <version> <outFolder> <baselineFile|-> <updateBaseline>"
                + " <shardReport.json>...");
            System.exit(2);
        }
        String version = args[0];
        Path outFolder = Path.of(args[1]);
        Path baselineFile = "-".equals(args[2]) ? null : Path.of(args[2]);
        boolean updateBaseline = Boolean.parseBoolean(args[3]);
        List<Path> shardFiles = new ArrayList<>();
        for (int i = 4; i < args.length; i++) shardFiles.add(Path.of(args[i]));
        try {
            run(version, outFolder, baselineFile, updateBaseline, shardFiles);
        } catch (MergeFailed e) {
            System.err.println("bench: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("bench: the merge could not read or write a report (" + e.getClass().getSimpleName() + ")");
            System.exit(1);
        }
    }

    private static final class MergeFailed extends RuntimeException {
        MergeFailed(String message) {
            super(message);
        }
    }

    private static void run(String version, Path outFolder, Path baselineFile, boolean updateBaseline,
                             List<Path> shardFiles) throws IOException {
        List<Map<String, ?>> shards = new ArrayList<>();
        for (Path file : shardFiles) {
            if (!Files.isRegularFile(file)) throw new MergeFailed("shard report missing: " + file);
            Map<String, Object> parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
            if (parsed == null) throw new MergeFailed("shard report is empty or not JSON: " + file);
            shards.add(parsed);
        }
        ReportMerge.Result result = ReportMerge.merge(shards);
        if (!result.ok()) throw new MergeFailed("merge refused: " + result.refusal());

        Map<String, Object> merged = new LinkedHashMap<>(result.report());
        // "schema" is an int in a plain run's own report (BenchReport.SCHEMA); Gson reads any JSON number
        // back as a Double when the target type is generic Object, which would otherwise write "1.0" here.
        if (merged.get("schema") instanceof Number schema) merged.put("schema", schema.intValue());
        Files.createDirectories(outFolder);
        Path jsonFile = outFolder.resolve("report-" + version + ".json");
        Path mdFile = outFolder.resolve("report-" + version + ".md");
        write(merged, jsonFile, mdFile);

        // The same two-pass hygiene scan BenchTest.hygiene() runs on a live report: scan what was just
        // written, record it, then scan once more in case the marker line itself looked like a position.
        List<String> hits = Hygiene.scan(outFolder);
        merged.put("hygiene", hits.isEmpty() ? BenchReport.HYGIENE_CLEAN : hits);
        write(merged, jsonFile, mdFile);
        if (hits.isEmpty()) {
            hits = Hygiene.scan(outFolder);
            if (!hits.isEmpty()) {
                merged.put("hygiene", hits);
                write(merged, jsonFile, mdFile);
            }
        }

        if (updateBaseline) updateBaseline(merged, baselineFile);

        System.out.println("bench: merged " + shardFiles.size() + " shard(s) into " + jsonFile.getFileName());
        if (!hits.isEmpty()) {
            for (String hit : hits) System.err.println("bench: hygiene: " + hit + " looks like a position");
        }
    }

    private static void write(Map<String, Object> merged, Path jsonFile, Path mdFile) throws IOException {
        Files.writeString(jsonFile, GSON.toJson(merged) + "\n", StandardCharsets.UTF_8);
        Files.writeString(mdFile, markdown(merged), StandardCharsets.UTF_8);
    }

    /**
     * {@code -Pbench.updateBaseline} from the MERGED report (task A5 requirement 3): a live shard only sees
     * its own slice of the scenarios, and shards 2..n run in a disposable worktree whose own
     * {@code bench/baseline.json} is never the committed one — so, unlike a plain run (which updates the
     * baseline itself, mid-run), a sharded run must not ask any shard to update it; this runs the exact
     * same step ({@link Baseline#put}) once, here, after the merge, over every DONE MEASURE of the complete,
     * merged report.
     */
    @SuppressWarnings("unchecked")
    private static void updateBaseline(Map<String, Object> merged, Path baselineFile) throws IOException {
        if (baselineFile == null) throw new MergeFailed("no baseline file to update (xploits.bench.baseline is not set)");
        Baseline baseline = Baseline.load(baselineFile);
        int put = 0;
        for (Object element : (List<Object>) merged.getOrDefault("scenarios", List.of())) {
            if (!(element instanceof Map<?, ?> raw)) continue;
            Map<String, Object> scenario = (Map<String, Object>) raw;
            if (!"MEASURE".equals(scenario.get("kind")) || !"DONE".equals(scenario.get("status"))) continue;
            baseline.put(str(scenario.get("name")), medians((Map<String, Object>) scenario.getOrDefault("median", Map.of())));
            put++;
        }
        baseline.write(baselineFile);
        System.out.println("bench: baseline updated with " + put + " scenario(s)");
    }

    // --- Markdown ----------------------------------------------------------------------------------
    // A reasonable, readable rendering of the same data BenchReport.markdown() tabulates for a single run;
    // benchVerify and ReleaseGate never read this file, only report-<version>.json, so exact byte-for-byte
    // parity with BenchReport's own renderer is not required — only that a human reads the same facts.

    @SuppressWarnings("unchecked")
    private static String markdown(Map<String, Object> report) {
        StringBuilder md = new StringBuilder();
        String addon = str(report.get("addon"));
        String meteor = str(report.get("meteor"));
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) (List<?>) report.getOrDefault("scenarios", List.of());
        md.append("# Bench report ").append(addon).append(" (merged)\n\n");
        md.append("Xploits ").append(addon).append(", Meteor ").append(meteor).append(", difficulty ")
            .append(str(report.get("difficulty"))).append(". ").append(summary(report, scenarios)).append(".\n\n");
        List<Object> recommendation = (List<Object>) report.getOrDefault("recommendation", List.of());
        for (Object line : recommendation) md.append(line).append(".\n\n");
        md.append("Merged (task A5) from every shard's own report; each scenario's numbers are its shard's,");
        md.append(" unchanged.\n\n");
        md.append("| Scenario | Kind | Status | Note |\n|---|---|---|---|\n");
        for (Map<String, Object> s : scenarios) {
            String note = s.get("error") != null ? str(s.get("error"))
                : s.get("skipped") != null ? str(s.get("skipped"))
                : Boolean.TRUE.equals(s.get("cached")) ? "cached, measured " + str(s.get("measured")) : "";
            md.append("| ").append(str(s.get("name"))).append(" | ").append(str(s.get("kind"))).append(" | ")
                .append(str(s.get("status"))).append(" | ").append(cell(note)).append(" |\n");
        }
        for (Map<String, Object> s : scenarios) {
            if ("MEASURE".equals(s.get("kind")) && !((List<?>) s.getOrDefault("runs", List.of())).isEmpty()) measure(md, s);
        }
        comparisons(md, scenarios);
        riskTable(md, report, scenarios);
        Object hygiene = report.get("hygiene");
        if (hygiene instanceof List<?> hits && !hits.isEmpty()) {
            md.append("\n## Hygiene\n\nThese lines look like a position:\n\n");
            for (Object hit : hits) md.append("- ").append(hit).append('\n');
        }
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private static String summary(Map<String, Object> report, List<Map<String, Object>> scenarios) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int regressions = 0;
        for (Map<String, Object> s : scenarios) {
            counts.merge(str(s.get("status")), 1, Integer::sum);
            regressions += ((List<?>) s.getOrDefault("regressions", List.of())).size();
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((status, n) -> parts.add(n + " " + status));
        if (regressions > 0) parts.add(regressions + " regression(s)");
        Object compare = report.get("compare");
        if (compare != null) parts.add(str(compare));
        Object hygiene = report.get("hygiene");
        if (hygiene instanceof String) parts.add("hygiene " + hygiene);
        else if (hygiene instanceof List) parts.add("hygiene ERROR");
        return str(report.get("profile")) + " run: " + (parts.isEmpty() ? "no scenario ran" : String.join(", ", parts));
    }

    @SuppressWarnings("unchecked")
    private static void measure(StringBuilder md, Map<String, Object> s) {
        Map<String, Object> median = (Map<String, Object>) s.getOrDefault("median", Map.of());
        Map<String, Object> min = (Map<String, Object>) s.getOrDefault("min", Map.of());
        Map<String, Object> max = (Map<String, Object>) s.getOrDefault("max", Map.of());
        List<Map<String, Object>> runs = (List<Map<String, Object>>) (List<?>) s.getOrDefault("runs", List.of());
        List<Object> noisy = (List<Object>) s.getOrDefault("noisy", List.of());
        List<Map<String, Object>> regressions = (List<Map<String, Object>>) (List<?>) s.getOrDefault("regressions", List.of());
        md.append("\n## ").append(str(s.get("name"))).append(" (").append(str(s.get("status"))).append(")\n\n");
        md.append("| Metric | Median | Min | Max");
        for (int i = 1; i <= runs.size(); i++) md.append(" | Run ").append(i);
        md.append(" | Flags |\n|---|---|---|---");
        for (int i = 0; i < runs.size(); i++) md.append("|---");
        md.append("|---|\n");
        for (String metric : median.keySet()) {
            md.append("| ").append(metric).append(" | ").append(number(median.get(metric))).append(" | ")
                .append(number(min.get(metric))).append(" | ").append(number(max.get(metric)));
            for (Map<String, Object> run : runs) md.append(" | ").append(number(run.get(metric)));
            List<String> flags = new ArrayList<>();
            if (noisy.contains(metric)) flags.add("noisy");
            if (regressions.stream().anyMatch(r -> metric.equals(r.get("metric")))) flags.add("REGRESSION");
            md.append(" | ").append(String.join(" and ", flags)).append(" |\n");
        }
    }

    @SuppressWarnings("unchecked")
    private static void comparisons(StringBuilder md, List<Map<String, Object>> scenarios) {
        boolean header = false;
        for (Map<String, Object> s : scenarios) {
            if (!(s.get("compare") instanceof Map<?, ?> raw)) continue;
            Map<String, Object> compare = (Map<String, Object>) raw;
            if (!header) {
                md.append("\n## crystal-aura++ against crystal-aura\n\n");
                header = true;
            }
            md.append("\n### ").append(str(s.get("name"))).append(" against ").append(str(compare.get("with")))
                .append(": ").append(str(compare.get("verdict"))).append("\n\n");
            if (compare.get("reason") != null) md.append("Why: ").append(compare.get("reason")).append(".\n\n");
            List<Map<String, Object>> rules = (List<Map<String, Object>>) (List<?>) compare.getOrDefault("rules", List.of());
            if (rules.isEmpty()) continue;
            md.append("| Rule | Kind | Result | What was compared |\n|---|---|---|---|\n");
            for (Map<String, Object> rule : rules) {
                md.append("| ").append(str(rule.get("rule"))).append(" | ").append(str(rule.get("kind"))).append(" | ")
                    .append(str(rule.get("result"))).append(" | ").append(cell(str(rule.get("detail")))).append(" |\n");
            }
        }
    }

    /** Reconstructs the risk table ({@link RiskTable}) from the merged scenarios plus each shard's own
     * (identical) {@code judged} map, the same medians a single run's own risk table would show. */
    @SuppressWarnings("unchecked")
    private static void riskTable(StringBuilder md, Map<String, Object> report, List<Map<String, Object>> scenarios) {
        Object judgedRaw = report.get("judged");
        if (!(judgedRaw instanceof Map<?, ?> judged)) return;
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> s : scenarios) byName.put(str(s.get("name")), s);
        Map<String, Map<RiskLevel, Map<String, Double>>> byMeteor = new LinkedHashMap<>();
        judged.forEach((name, levelName) -> {
            Map<String, Object> scenario = byName.get(String.valueOf(name));
            if (!(scenario.get("compare") instanceof Map<?, ?> compare)) return;
            String with = str(compare.get("with"));
            RiskLevel level = RiskLevel.valueOf(String.valueOf(levelName));
            byMeteor.computeIfAbsent(with, key -> new java.util.EnumMap<>(RiskLevel.class))
                .put(level, medians((Map<String, Object>) scenario.getOrDefault("median", Map.of())));
        });
        List<RiskTable.Row> rows = new ArrayList<>();
        for (Map.Entry<String, Map<RiskLevel, Map<String, Double>>> entry : byMeteor.entrySet()) {
            Map<String, Object> meteorScenario = byName.get(entry.getKey());
            Map<String, Double> meteorMedian = meteorScenario == null ? Map.of()
                : medians((Map<String, Object>) meteorScenario.getOrDefault("median", Map.of()));
            rows.add(new RiskTable.Row(entry.getKey(), meteorMedian, entry.getValue()));
        }
        List<String> lines = RiskTable.lines(rows);
        if (lines.isEmpty()) return;
        md.append("\n## crystal-aura++ risk levels\n\n");
        for (String line : lines) md.append(line).append('\n');
    }

    private static Map<String, Double> medians(Map<String, Object> median) {
        Map<String, Double> out = new LinkedHashMap<>();
        median.forEach((k, v) -> out.put(k, ((Number) v).doubleValue()));
        return out;
    }

    private static String number(Object value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", ((Number) value).doubleValue());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
