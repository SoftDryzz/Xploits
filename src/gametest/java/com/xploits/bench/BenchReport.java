package com.xploits.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bench report (spec {@code 2026-09-25-ingame-bench}, §Report and baseline): {@code
 * report-<version>.json} and an English {@code .md} table, both rewritten after every run so a crash
 * still leaves what ran. Numbers, names, statuses and bench-written messages only: never a position.
 */
public final class BenchReport {
    public static final int SCHEMA = 1;
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

    private final String addon;
    private final String meteor;
    private final Path folder;
    /** The scenarios with at least one run, in the order they ran. */
    private final Map<String, Entry> scenarios = new LinkedHashMap<>();

    private static final class Entry {
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
    }

    public BenchReport(String addon, String meteor, Path folder) {
        this.addon = addon;
        this.meteor = meteor;
        this.folder = folder;
    }

    public void add(Scenario scenario, Run run) {
        scenarios.computeIfAbsent(scenario.name(), name -> new Entry(scenario)).runs.add(run);
    }

    /** Whether any scenario is FAIL or ERROR. */
    public boolean failed() {
        return scenarios.values().stream().anyMatch(e -> e.status().fails());
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
        return parts.isEmpty() ? "no scenario ran" : String.join(", ", parts);
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
            for (Run run : e.runs) {
                JsonObject values = new JsonObject();
                run.metrics().forEach(values::addProperty);
                runs.add(values);
            }
            s.add("runs", runs);
            list.add(s);
        }
        root.add("scenarios", list);
        return root;
    }

    private String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Bench report ").append(addon).append("\n\n");
        md.append("Xploits ").append(addon).append(", Meteor ").append(meteor).append(", difficulty normal. ")
            .append(summary()).append(".\n\n");
        md.append("| Scenario | Kind | Runs | Status | Error |\n");
        md.append("|---|---|---|---|---|\n");
        for (Entry e : scenarios.values()) {
            String error = e.error();
            md.append("| ").append(e.scenario.name())
                .append(" | ").append(e.scenario.kind().name())
                .append(" | ").append(e.runs.size()).append('/').append(e.scenario.runs())
                .append(" | ").append(e.status().name())
                .append(" | ").append(error == null ? "" : error.replace("|", "\\|"))
                .append(" |\n");
        }
        return md.toString();
    }
}
