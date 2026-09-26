package com.xploits.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The committed MEASURE baseline, {@code bench/baseline.json} (spec {@code 2026-09-25-ingame-bench},
 * §Report and baseline): {@code { "schema": 1, "scenarios": { name: { metric: median } } }}, numbers
 * only. A missing file or entry means there is nothing to compare with.
 */
final class Baseline {
    static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Scenario name to its medians, in the file's order. */
    private final Map<String, Map<String, Double>> scenarios = new LinkedHashMap<>();

    private Baseline() {
    }

    /** An empty baseline: nothing is compared. */
    static Baseline empty() {
        return new Baseline();
    }

    /**
     * Reads the file; a missing one gives an empty baseline. A file that does not parse, or has another
     * schema or a value that is not a number, is an error the bench wrote (never the parser's text).
     */
    static Baseline load(Path file) {
        Baseline baseline = new Baseline();
        if (file == null || !Files.exists(file)) return baseline;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA) {
                throw new BenchException("the bench baseline has another schema than " + SCHEMA);
            }
            JsonObject list = root.getAsJsonObject("scenarios");
            if (list == null) throw new BenchException("the bench baseline has no scenarios");
            for (Map.Entry<String, JsonElement> scenario : list.entrySet()) {
                Map<String, Double> medians = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> metric : scenario.getValue().getAsJsonObject().entrySet()) {
                    Metrics.definition(metric.getKey());
                    double value = metric.getValue().getAsDouble();
                    if (!Double.isFinite(value)) throw new BenchException("the bench baseline holds something that is not a number");
                    medians.put(metric.getKey(), value);
                }
                baseline.scenarios.put(scenario.getKey(), medians);
            }
        } catch (IOException | IllegalStateException | UnsupportedOperationException | ClassCastException
                 | NumberFormatException | JsonParseException e) {
            throw new BenchException("the bench baseline could not be read (" + e.getClass().getSimpleName() + ")");
        }
        return baseline;
    }

    /** A scenario's medians, or null when the baseline has no entry for it. */
    Map<String, Double> scenario(String name) {
        Map<String, Double> medians = scenarios.get(name);
        return medians == null ? null : Collections.unmodifiableMap(medians);
    }

    int size() {
        return scenarios.size();
    }

    /** Replaces one scenario's entry with these medians (rounded as the report rounds them); the others stay. */
    void put(String name, Map<String, Double> medians) {
        Map<String, Double> rounded = new LinkedHashMap<>();
        medians.forEach((metric, value) -> rounded.put(metric, BenchReport.round(value)));
        scenarios.put(name, rounded);
    }

    void write(Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonObject list = new JsonObject();
        scenarios.forEach((name, medians) -> {
            JsonObject values = new JsonObject();
            medians.forEach(values::addProperty);
            list.add(name, values);
        });
        root.add("scenarios", list);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
    }
}
