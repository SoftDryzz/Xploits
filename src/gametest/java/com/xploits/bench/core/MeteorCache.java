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
 * under a {@link Key} over what can change them: the bytes of Meteor's jar on the game's classpath, the
 * Minecraft version, the project files of {@link #inputs} (the bench, the build files, the mixins; by sorted
 * path, with their bytes) and the scenario's name. A release re-measures Meteor anyway ({@link ReleaseGate}). The next run serves them instead of playing the scenario, but only while the key
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
     * A SHA-256 over Meteor's jar, the Minecraft version and the project files, to which {@link #of} adds a
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
         * @param sources          the key's project files, each path relative to the project with {@code /},
         *                         to its bytes ({@link MeteorCache#inputs})
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

    /** The build files that pin what the game loads: Fabric API, loader, yarn, Loom, Meteor and Minecraft versions. */
    public static final List<String> BUILD_FILES =
        List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml");
    private static final String BENCH = "src/gametest";
    private static final String RESOURCES = "src/main/resources";
    private static final String MAIN_JAVA = "src/main/java";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /**
     * Every project file whose change can change what Meteor's crystal-aura does in the bench, by its path
     * relative to {@code project} with {@code /}, sorted, with its bytes: every file under {@code src/gametest}
     * (the bench itself), the {@link #BUILD_FILES} that exist (the versions of everything the game loads), every
     * mixin config under {@code src/main/resources}, and every Java file under each of their {@code package}s in
     * {@code src/main/java}, sub-packages included (the addon's mixins reach into Minecraft, and so into what
     * Meteor sees). The rest of the addon is left out on purpose: crystal-aura++ is off in every {@code ca-*} run,
     * and hashing it would re-measure Meteor on every crystal-aura++ change, which would defeat the cache. A
     * missing {@code src/gametest}, or a mixin package that is not a Java package name, is an error.
     */
    public static SortedMap<String, byte[]> inputs(Path project) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        sources(project.resolve(BENCH)).forEach((path, bytes) -> files.put(BENCH + "/" + path, bytes));
        for (String name : BUILD_FILES) {
            Path file = project.resolve(name);
            if (Files.isRegularFile(file)) files.put(name, Files.readAllBytes(file));
        }
        Path resources = project.resolve(RESOURCES);
        if (!Files.isDirectory(resources)) return files;
        for (Map.Entry<String, byte[]> resource : sources(resources).entrySet()) {
            if (!resource.getKey().endsWith(".json")) continue;
            String text = new String(resource.getValue(), StandardCharsets.UTF_8);
            MixinConfig config = mixinConfig(text);
            if (config == MixinConfig.NOT_ONE) continue;
            files.put(RESOURCES + "/" + resource.getKey(), resource.getValue());
            if (config.pkg() == null) continue;
            if (!PACKAGE.matcher(config.pkg()).matches()) {
                throw new IOException("a mixin config's package is not a Java package name");
            }
            String folder = MAIN_JAVA + "/" + config.pkg().replace('.', '/');
            Path packageRoot = project.resolve(folder);
            if (!Files.isDirectory(packageRoot)) continue;
            sources(packageRoot).forEach((path, bytes) -> {
                if (path.endsWith(".java")) files.put(folder + "/" + path, bytes);
            });
        }
        return files;
    }

    /** What a JSON under the resources is: not a mixin config, or one with its package (null if it cannot be read). */
    private record MixinConfig(String pkg) {
        static final MixinConfig NOT_ONE = new MixinConfig("");
    }

    /**
     * A mixin config is an object with a string {@code package} and a {@code mixins}, {@code client} or
     * {@code server} list. Text that does not parse might be one, so it counts, with no package to follow.
     */
    private static MixinConfig mixinConfig(String text) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return new MixinConfig(null);
        }
        if (!parsed.isJsonObject()) return MixinConfig.NOT_ONE;
        JsonObject root = parsed.getAsJsonObject();
        String pkg = string(root, "package");
        boolean lists = List.of("mixins", "client", "server").stream()
            .anyMatch(name -> root.get(name) != null && root.get(name).isJsonArray());
        return pkg != null && lists ? new MixinConfig(pkg) : MixinConfig.NOT_ONE;
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
