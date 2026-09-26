package com.xploits.bench.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Meteor cache (R3-9). Meteor's crystal-aura gives the same numbers run after run, so once a {@code ca-*}
 * MEASURE has finished DONE its runs' metrics are kept in {@code build/bench/meteor-cache/<scenario>.json},
 * under a {@link Key} over everything that could change them: the bytes of Meteor's jar on the game's
 * classpath, the Minecraft version, every file under {@code src/gametest/} (by sorted path, with its bytes)
 * and the scenario's name. The next run serves them instead of playing the scenario, but only while the key
 * still matches: a missing file, another key, or a file that cannot be read as one is a miss
 * ({@link Lookup}), and the scenario is measured again. Reading never throws. Pure: no game class.
 */
public final class MeteorCache {
    /** The cache file's own format. */
    public static final int SCHEMA = 1;
    /** The folder under {@code build/bench} the files go in. */
    public static final String FOLDER = "meteor-cache";
    /** A scenario name that is safe as a file name: {@code ca-still}, {@code ca-above-regen}. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MeteorCache() {
    }

    /**
     * A SHA-256 over Meteor's jar, the Minecraft version and the bench's sources, to which {@link #of} adds a
     * scenario's name. Every field goes in with its length first, so bytes moved from one field to the next
     * never give the same key. The shared part is hashed once and copied for each scenario.
     */
    public static final class Key {
        private static final byte[] DOMAIN = "xploits bench meteor cache 1".getBytes(StandardCharsets.UTF_8);
        private final MessageDigest shared;

        private Key(MessageDigest shared) {
            this.shared = shared;
        }

        /**
         * @param meteorJar        the bytes of the Meteor jar the game loaded
         * @param minecraftVersion the game's version
         * @param sources          every file under {@code src/gametest/}: its path relative to it, with
         *                         {@code /}, to its bytes ({@link MeteorCache#sources})
         */
        public static Key of(byte[] meteorJar, String minecraftVersion, SortedMap<String, byte[]> sources) {
            MessageDigest digest = sha256();
            field(digest, DOMAIN);
            field(digest, meteorJar);
            field(digest, utf8(minecraftVersion));
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(sources.size()).array());
            sources.forEach((path, bytes) -> {
                field(digest, utf8(path));
                field(digest, bytes);
            });
            return new Key(digest);
        }

        /** The key of one scenario, as 64 lowercase hex digits. */
        public String of(String scenario) {
            MessageDigest digest;
            try {
                digest = (MessageDigest) shared.clone();
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException("SHA-256 cannot be copied", e);
            }
            field(digest, utf8(scenario));
            return HexFormat.of().formatHex(digest.digest());
        }

        private static void field(MessageDigest digest, byte[] bytes) {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
            digest.update(bytes);
        }

        private static byte[] utf8(String text) {
            return Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8);
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("every Java has SHA-256", e);
            }
        }
    }

    /**
     * One cached scenario: its name, the key it was measured under, the date it was measured
     * ({@code yyyy-mm-dd}), and each DONE run's metrics, in run order.
     */
    public record Entry(String scenario, String key, String measured, List<Map<String, Double>> runs) {
        public Entry {
            checkName(scenario);
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(measured, "measured");
            List<Map<String, Double>> copy = new ArrayList<>();
            for (Map<String, Double> run : runs) {
                for (Map.Entry<String, Double> metric : run.entrySet()) {
                    Objects.requireNonNull(metric.getKey(), "metric");
                    if (metric.getValue() == null || !Double.isFinite(metric.getValue())) {
                        throw new IllegalArgumentException(metric.getKey() + " is not a finite number");
                    }
                }
                copy.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(run)));
            }
            runs = List.copyOf(copy);
        }
    }

    /** What a read found: the entry (a hit), or why there is none (a miss). */
    public record Lookup(Entry entry, String miss) {
        static Lookup hit(Entry entry) {
            return new Lookup(entry, null);
        }

        static Lookup miss(String why) {
            return new Lookup(null, why);
        }

        public boolean hit() {
            return entry != null;
        }
    }

    /**
     * Every regular file under {@code root}, by its path relative to it with {@code /}, sorted, with its
     * bytes. A missing folder is an error: without the sources there is no key.
     */
    public static SortedMap<String, byte[]> sources(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("no folder of bench sources");
        SortedMap<String, byte[]> files = new TreeMap<>();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(Files::isRegularFile).toList();
        }
        for (Path path : paths) files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        return files;
    }

    /** {@code <folder>/<scenario>.json}. */
    public static Path file(Path folder, String scenario) {
        return folder.resolve(checkName(scenario) + ".json");
    }

    /** Writes (or overwrites) the file, one number per line. */
    public static void write(Path file, Entry entry) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("scenario", entry.scenario());
        root.addProperty("key", entry.key());
        root.addProperty("measured", entry.measured());
        JsonArray runs = new JsonArray();
        for (Map<String, Double> run : entry.runs()) {
            JsonObject metrics = new JsonObject();
            run.forEach(metrics::addProperty);
            runs.add(metrics);
        }
        root.add("runs", runs);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * The cached runs of {@code scenario}, when the file holds exactly {@code runs} runs of it measured under
     * {@code key}; otherwise a miss that says why. Never throws: a file that cannot be read, or does not hold
     * what it should, is a miss like any other.
     */
    public static Lookup read(Path file, String scenario, String key, int runs) {
        if (!Files.exists(file)) return Lookup.miss("not cached");
        Entry entry;
        try {
            entry = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            entry = null;
        }
        if (entry == null) return Lookup.miss("the cached file could not be read");
        if (!entry.scenario().equals(scenario)) return Lookup.miss("the cached file is another scenario's");
        if (!entry.key().equals(key)) return Lookup.miss("cached under another key");
        if (entry.runs().size() != runs) return Lookup.miss("cached with " + entry.runs().size() + " run(s), not " + runs);
        return Lookup.hit(entry);
    }

    /** The entry the text holds, or null when it is not one; may throw on text that is not JSON. */
    private static Entry parse(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject();
        JsonPrimitive schema = primitive(root, "schema");
        if (schema == null || !schema.isNumber() || schema.getAsInt() != SCHEMA) return null;
        String scenario = string(root, "scenario");
        String key = string(root, "key");
        String measured = string(root, "measured");
        if (scenario == null || key == null || measured == null) return null;
        JsonElement runsElement = root.get("runs");
        if (runsElement == null || !runsElement.isJsonArray()) return null;
        List<Map<String, Double>> runs = new ArrayList<>();
        for (JsonElement runElement : runsElement.getAsJsonArray()) {
            if (!runElement.isJsonObject()) return null;
            Map<String, Double> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> metric : runElement.getAsJsonObject().entrySet()) {
                JsonElement value = metric.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
                double number = value.getAsDouble();
                if (!Double.isFinite(number)) return null;
                metrics.put(metric.getKey(), number);
            }
            runs.add(metrics);
        }
        return new Entry(scenario, key, measured, runs);
    }

    private static JsonPrimitive primitive(JsonObject root, String name) {
        JsonElement element = root.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsJsonPrimitive() : null;
    }

    private static String string(JsonObject root, String name) {
        JsonPrimitive primitive = primitive(root, name);
        return primitive != null && primitive.isString() ? primitive.getAsString() : null;
    }

    private static String checkName(String scenario) {
        if (scenario == null || !NAME.matcher(scenario).matches()) {
            throw new IllegalArgumentException("not a scenario name the cache can file: " + scenario);
        }
        return scenario;
    }
}
