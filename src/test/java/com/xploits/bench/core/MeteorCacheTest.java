package com.xploits.bench.core;

import com.xploits.bench.core.MeteorCache.Entry;
import com.xploits.bench.core.MeteorCache.Key;
import com.xploits.bench.core.MeteorCache.Lookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Meteor cache (R3-9): Meteor's crystal-aura gives the same numbers run after run, so a {@code ca-*}
 * MEASURE's runs are kept under a key over everything that could change them (Meteor's jar, the Minecraft
 * version, every bench source, the scenario's name) and served again only while that key still matches. A
 * missing, stale or broken file is a miss, never a crash.
 */
class MeteorCacheTest {
    private static final byte[] METEOR = bytes("meteor jar bytes");
    private static final String MINECRAFT = "1.21.11";

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static SortedMap<String, byte[]> sources() {
        SortedMap<String, byte[]> sources = new TreeMap<>();
        sources.put("java/com/xploits/bench/Still.java", bytes("class Still {}"));
        sources.put("java/com/xploits/bench/Circler.java", bytes("class Circler {}"));
        sources.put("resources/fabric.mod.json", bytes("{}"));
        return sources;
    }

    private static String key(byte[] meteor, String minecraft, SortedMap<String, byte[]> sources, String scenario) {
        return Key.of(meteor, minecraft, sources).of(scenario);
    }

    private static String key() {
        return key(METEOR, MINECRAFT, sources(), "ca-still");
    }

    private static List<Map<String, Double>> runs() {
        Map<String, Double> run = new LinkedHashMap<>();
        run.put("damage_dealt", 41.25);
        run.put("sparring_pops", 1.0);
        run.put("min_health", 13.0);
        Map<String, Double> other = new LinkedHashMap<>(run);
        other.put("damage_dealt", 40.123456789);
        return List.of(run, other, run);
    }

    // The key

    @Test
    void theKeyIsAStableSha256() {
        String key = key();
        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"), key);
        assertEquals(key, key());
    }

    @Test
    void theKeyChangesWithMeteorsJar() {
        assertNotEquals(key(), key(bytes("meteor jar bytez"), MINECRAFT, sources(), "ca-still"));
    }

    @Test
    void theKeyChangesWithTheMinecraftVersion() {
        assertNotEquals(key(), key(METEOR, "1.21.12", sources(), "ca-still"));
    }

    @Test
    void theKeyChangesWithTheScenario() {
        assertNotEquals(key(), key(METEOR, MINECRAFT, sources(), "ca-circler"));
    }

    @Test
    void theKeyChangesWithAnyBenchSourceByte() {
        SortedMap<String, byte[]> changed = sources();
        changed.put("java/com/xploits/bench/Still.java", bytes("class Still { }"));
        assertNotEquals(key(), key(METEOR, MINECRAFT, changed, "ca-still"));
    }

    @Test
    void theKeyChangesWhenABenchSourceIsAddedRemovedOrRenamed() {
        SortedMap<String, byte[]> added = sources();
        added.put("java/com/xploits/bench/Strafe.java", bytes(""));
        assertNotEquals(key(), key(METEOR, MINECRAFT, added, "ca-still"));

        SortedMap<String, byte[]> removed = sources();
        removed.remove("resources/fabric.mod.json");
        assertNotEquals(key(), key(METEOR, MINECRAFT, removed, "ca-still"));

        SortedMap<String, byte[]> renamed = sources();
        renamed.put("java/com/xploits/bench/Still2.java", renamed.remove("java/com/xploits/bench/Still.java"));
        assertNotEquals(key(), key(METEOR, MINECRAFT, renamed, "ca-still"));
    }

    @Test
    void theKeyKeepsItsFieldsApart() {
        // Bytes moved from one field to the next must not give the same key.
        SortedMap<String, byte[]> one = new TreeMap<>();
        one.put("a", bytes("bc"));
        SortedMap<String, byte[]> two = new TreeMap<>();
        two.put("ab", bytes("c"));
        assertNotEquals(key(METEOR, MINECRAFT, one, "ca-still"), key(METEOR, MINECRAFT, two, "ca-still"));
        assertNotEquals(key(bytes("ab"), "c", sources(), "ca-still"), key(bytes("a"), "bc", sources(), "ca-still"));
        assertNotEquals(key(METEOR, "1.21.11c", sources(), "a-still"), key(METEOR, "1.21.11", sources(), "ca-still"));
    }

    @Test
    void oneKeyServesEveryScenarioAlike() {
        Key shared = Key.of(METEOR, MINECRAFT, sources());
        String first = shared.of("ca-still");
        shared.of("ca-circler");
        assertEquals(first, shared.of("ca-still"));
    }

    @Test
    void theSourcesAreEveryFileUnderTheFolderBySortedPath(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("java/com/xploits/bench/core"));
        Files.createDirectories(root.resolve("resources"));
        Files.writeString(root.resolve("java/com/xploits/bench/Still.java"), "still");
        Files.writeString(root.resolve("java/com/xploits/bench/core/Settle.java"), "settle");
        Files.writeString(root.resolve("resources/fabric.mod.json"), "{}");
        SortedMap<String, byte[]> files = MeteorCache.sources(root);
        assertEquals(List.of("java/com/xploits/bench/Still.java", "java/com/xploits/bench/core/Settle.java",
            "resources/fabric.mod.json"), List.copyOf(files.keySet()));
        assertArrayEquals(bytes("settle"), files.get("java/com/xploits/bench/core/Settle.java"));
    }

    @Test
    void aMissingSourcesFolderIsAnError(@TempDir Path root) {
        assertThrows(IOException.class, () -> MeteorCache.sources(root.resolve("absent")));
    }

    // The project inputs (fix round 1): what can change Meteor's behaviour in the game besides src/gametest

    private static void put(Path project, String path, String text) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static final String MIXINS = "{\"required\": true, \"package\": \"com.xploits.mixin\", \"client\": [\"ChatHudMixin\"]}";

    /** A project like the real one: the bench, the build files, a mixin config and its package, and other code. */
    private static void project(Path project) throws IOException {
        put(project, "src/gametest/java/com/xploits/bench/Still.java", "class Still {}");
        put(project, "src/gametest/resources/fabric.mod.json", "{}");
        put(project, "build.gradle.kts", "fabric-api 0.141.4");
        put(project, "settings.gradle.kts", "rootProject.name = \"xploits\"");
        put(project, "gradle.properties", "org.gradle.jvmargs=-Xmx2G");
        put(project, "gradle/libs.versions.toml", "meteor = \"1.21.11-SNAPSHOT\"");
        put(project, "src/main/resources/xploits.mixins.json", MIXINS);
        put(project, "src/main/resources/fabric.mod.json", "{\"mixins\": [\"xploits.mixins.json\"]}");
        put(project, "src/main/resources/assets/xploits/lang/en_us.json", "{\"key\": \"text\"}");
        put(project, "src/main/java/com/xploits/mixin/ChatHudMixin.java", "class ChatHudMixin {}");
        put(project, "src/main/java/com/xploits/mixin/inner/Deep.java", "class Deep {}");
        put(project, "src/main/java/com/xploits/pvp/crystal/CrystalAuraPlusPlus.java", "class CrystalAuraPlusPlus {}");
        put(project, "src/main/java/com/xploits/mixinlike/NotAMixin.java", "class NotAMixin {}");
    }

    private static String projectKey(Path project) throws IOException {
        return Key.of(METEOR, MINECRAFT, MeteorCache.inputs(project)).of("ca-still");
    }

    @Test
    void theInputsAreTheBenchTheBuildFilesAndTheMixins(@TempDir Path project) throws IOException {
        project(project);
        assertEquals(List.of(
                "build.gradle.kts",
                "gradle.properties",
                "gradle/libs.versions.toml",
                "settings.gradle.kts",
                "src/gametest/java/com/xploits/bench/Still.java",
                "src/gametest/resources/fabric.mod.json",
                "src/main/java/com/xploits/mixin/ChatHudMixin.java",
                "src/main/java/com/xploits/mixin/inner/Deep.java",
                "src/main/resources/xploits.mixins.json"),
            List.copyOf(MeteorCache.inputs(project).keySet()));
    }

    @Test
    void theKeyChangesWithEachBuildFile(@TempDir Path project) throws IOException {
        project(project);
        for (String file : List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")) {
            String before = projectKey(project);
            put(project, file, "changed " + file);
            assertNotEquals(before, projectKey(project), file);
        }
        String before = projectKey(project);
        Files.delete(project.resolve("settings.gradle.kts"));
        assertNotEquals(before, projectKey(project), "a build file removed");
    }

    @Test
    void theKeyChangesWithAMixinConfig(@TempDir Path project) throws IOException {
        project(project);
        String before = projectKey(project);
        put(project, "src/main/resources/xploits.mixins.json", MIXINS.replace("\"ChatHudMixin\"", "\"ChatHudMixin\", \"Other\""));
        assertNotEquals(before, projectKey(project));
    }

    @Test
    void theKeyChangesWithAMixinClass(@TempDir Path project) throws IOException {
        project(project);
        String before = projectKey(project);
        put(project, "src/main/java/com/xploits/mixin/ChatHudMixin.java", "class ChatHudMixin { int changed; }");
        String edited = projectKey(project);
        assertNotEquals(before, edited);
        put(project, "src/main/java/com/xploits/mixin/inner/Deep.java", "class Deep { int changed; }");
        String deep = projectKey(project);
        assertNotEquals(edited, deep, "a mixin in a sub-package");
        put(project, "src/main/java/com/xploits/mixin/NewMixin.java", "class NewMixin {}");
        assertNotEquals(deep, projectKey(project), "a mixin added");
    }

    @Test
    void theKeyKeepsCrystalAuraPlusPlusAndTheRestOfTheAddonOut(@TempDir Path project) throws IOException {
        // Hashing all of src/main would re-measure Meteor on every crystal-aura++ change and defeat the cache.
        project(project);
        String before = projectKey(project);
        put(project, "src/main/java/com/xploits/pvp/crystal/CrystalAuraPlusPlus.java", "class CrystalAuraPlusPlus { int tuned; }");
        put(project, "src/main/java/com/xploits/mixinlike/NotAMixin.java", "class NotAMixin { int changed; }");
        put(project, "src/main/resources/assets/xploits/lang/en_us.json", "{\"key\": \"other text\"}");
        put(project, "src/main/resources/fabric.mod.json", "{\"mixins\": [\"xploits.mixins.json\"], \"version\": \"2\"}");
        put(project, "README.md", "notes");
        assertEquals(before, projectKey(project));
    }

    @Test
    void theMixinPackagesComeFromTheConfigs(@TempDir Path project) throws IOException {
        // Never a hard-coded package: a second config with its own package brings that package in.
        project(project);
        put(project, "src/main/resources/sub/other.mixins.json", "{\"package\": \"org.example.hooks\", \"mixins\": [\"Hook\"]}");
        put(project, "src/main/java/org/example/hooks/Hook.java", "class Hook {}");
        String before = projectKey(project);
        assertTrue(MeteorCache.inputs(project).containsKey("src/main/java/org/example/hooks/Hook.java"));
        assertTrue(MeteorCache.inputs(project).containsKey("src/main/resources/sub/other.mixins.json"));
        put(project, "src/main/java/org/example/hooks/Hook.java", "class Hook { int changed; }");
        assertNotEquals(before, projectKey(project));
    }

    @Test
    void aMixinConfigThatDoesNotParseStillCounts(@TempDir Path project) throws IOException {
        // A JSON under src/main/resources that cannot be read might be a mixin config: it is kept, never guessed away.
        project(project);
        put(project, "src/main/resources/broken.mixins.json", "{ not json");
        assertTrue(MeteorCache.inputs(project).containsKey("src/main/resources/broken.mixins.json"));
    }

    @Test
    void aMixinPackageThatIsNotAPackageIsAnError(@TempDir Path project) throws IOException {
        project(project);
        put(project, "src/main/resources/bad.mixins.json", "{\"package\": \"../../outside\", \"mixins\": [\"X\"]}");
        assertThrows(IOException.class, () -> MeteorCache.inputs(project));
    }

    @Test
    void aProjectWithoutTheBenchIsAnError(@TempDir Path project) throws IOException {
        put(project, "build.gradle.kts", "x");
        assertThrows(IOException.class, () -> MeteorCache.inputs(project));
    }

    // The file

    @Test
    void whatIsWrittenIsReadBackUnderTheSameKey(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        assertEquals(folder.resolve("ca-still.json"), file);
        MeteorCache.write(file, new Entry("ca-still", key(), "2026-09-27", runs()));
        Lookup lookup = MeteorCache.read(file, "ca-still", key(), 3);
        assertTrue(lookup.hit(), lookup.miss());
        assertEquals(runs(), lookup.entry().runs());
        assertEquals("2026-09-27", lookup.entry().measured());
        assertEquals(List.copyOf(runs().getFirst().keySet()), List.copyOf(lookup.entry().runs().getFirst().keySet()));
    }

    @Test
    void anotherKeyIsAMiss(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        MeteorCache.write(file, new Entry("ca-still", key(), "2026-09-27", runs()));
        Lookup lookup = MeteorCache.read(file, "ca-still", key(bytes("new meteor"), MINECRAFT, sources(), "ca-still"), 3);
        assertFalse(lookup.hit());
        assertEquals("cached under another key", lookup.miss());
    }

    @Test
    void aMissingFileIsAMiss(@TempDir Path folder) {
        Lookup lookup = MeteorCache.read(MeteorCache.file(folder, "ca-still"), "ca-still", key(), 3);
        assertFalse(lookup.hit());
        assertEquals("not cached", lookup.miss());
    }

    @Test
    void anotherScenariosFileIsAMiss(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        MeteorCache.write(file, new Entry("ca-circler", key(), "2026-09-27", runs()));
        assertFalse(MeteorCache.read(file, "ca-still", key(), 3).hit());
    }

    @Test
    void anotherRunCountIsAMiss(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        MeteorCache.write(file, new Entry("ca-still", key(), "2026-09-27", runs().subList(0, 2)));
        Lookup lookup = MeteorCache.read(file, "ca-still", key(), 3);
        assertFalse(lookup.hit());
        assertEquals("cached with 2 run(s), not 3", lookup.miss());
    }

    @Test
    void aBrokenFileIsAMissNeverACrash(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        String key = key();
        List<String> broken = List.of(
            "",
            "not json at all",
            "{",
            "[]",
            "{\"schema\": 1}",
            "{\"schema\": 2, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\", \"runs\": [{}, {}, {}]}",
            "{\"schema\": \"one\", \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\", \"runs\": [{}, {}, {}]}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\", \"runs\": {}}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\", \"runs\": [1, 2, 3]}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\","
                + " \"runs\": [{\"damage_dealt\": \"lots\"}, {}, {}]}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\","
                + " \"runs\": [{\"damage_dealt\": [1]}, {}, {}]}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\","
                + " \"runs\": [{\"damage_dealt\": NaN}, {}, {}]}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"runs\": [{}, {}, {}]}",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key + "\", \"measured\": \"2026-09-27\","
                + " \"runs\": [{\"damage_dealt\": 1}, {}, {}]",
            "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": null, \"measured\": \"2026-09-27\", \"runs\": [{}, {}, {}]}");
        for (String text : broken) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            Lookup lookup = MeteorCache.read(file, "ca-still", key, 3);
            assertFalse(lookup.hit(), text);
        }
        Files.write(file, new byte[] {(byte) 0xff, (byte) 0xfe, 0, 1, 2});
        assertFalse(MeteorCache.read(file, "ca-still", key, 3).hit());
    }

    @Test
    void aFolderWhereTheFileShouldBeIsAMiss(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        Files.createDirectories(file);
        assertFalse(MeteorCache.read(file, "ca-still", key(), 3).hit());
    }

    @Test
    void aValidFileWrittenByHandIsAHit(@TempDir Path folder) throws IOException {
        Path file = MeteorCache.file(folder, "ca-still");
        Files.writeString(file, "{\"schema\": 1, \"scenario\": \"ca-still\", \"key\": \"" + key() + "\", \"measured\": \"2026-09-27\","
            + " \"runs\": [{\"damage_dealt\": 1}, {\"damage_dealt\": 2}, {\"damage_dealt\": 3.5}]}", StandardCharsets.UTF_8);
        Lookup lookup = MeteorCache.read(file, "ca-still", key(), 3);
        assertTrue(lookup.hit(), lookup.miss());
        assertEquals(3.5, lookup.entry().runs().get(2).get("damage_dealt"));
    }

    @Test
    void theWrittenFileHasOneNumberPerLine(@TempDir Path folder) throws IOException {
        // The hygiene scan reads every .json under build/bench, the cache included.
        Path file = MeteorCache.file(folder, "ca-still");
        MeteorCache.write(file, new Entry("ca-still", key(), "2026-09-27", runs()));
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) assertFalse(PositionLike.in(line), line);
    }

    @Test
    void anEntryHoldsOnlyFiniteNumbersAndAFileSafeName() {
        List<Map<String, Double>> nan = List.of(Map.of("damage_dealt", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Entry("ca-still", key(), "2026-09-27", nan));
        assertThrows(IllegalArgumentException.class, () -> new Entry("../ca-still", key(), "2026-09-27", runs()));
        assertThrows(IllegalArgumentException.class, () -> MeteorCache.file(Path.of("x"), "ca/still"));
        assertThrows(NullPointerException.class, () -> new Entry("ca-still", null, "2026-09-27", runs()));
    }
}
