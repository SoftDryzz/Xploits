package com.xploits.pvp.recorder.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.FightRecord.ModuleChange;
import com.xploits.pvp.recorder.core.FightRecord.Opponent;
import com.xploits.pvp.recorder.core.FightRecord.PhaseChange;
import com.xploits.pvp.recorder.core.FightRecord.Sample;
import com.xploits.pvp.recorder.core.FightRecord.SelfTotals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FightStore round-trips fight-<startedAt>.json files and never lets a bad one through (spec §FightStore). */
class FightStoreTest {
    private static final Pattern FORBIDDEN_KEY = Pattern.compile("(?i)^(x|y|z|pos|position|coord|coords|dimension)$");

    @Test
    void roundTripKeepsEveryField(@TempDir Path dir) throws IOException {
        FightStore store = new FightStore(dir);
        FightRecord f = realisticFight();

        Path saved = store.save(f);
        assertEquals("fight-" + f.startedAt() + ".json", saved.getFileName().toString());

        FightRecord loaded = store.load(saved);
        assertEquals(f, loaded);
        // called out by the brief: a null nearestHostile must round-trip as null, not 0 or dropped
        assertNull(loaded.samples().get(1).nearestHostile());
        assertEquals(5.2, loaded.samples().get(0).nearestHostile());
    }

    @Test
    void listIsNewestFirstAndIgnoresTmpAndForeignFiles(@TempDir Path dir) throws IOException {
        FightStore store = new FightStore(dir);
        store.save(fightAt(1_700_000_001_000L));
        store.save(fightAt(1_700_000_002_000L));
        store.save(fightAt(1_700_000_003_000L));
        Files.writeString(dir.resolve("fight-1700000004000.json.tmp"), "not finished");
        Files.writeString(dir.resolve("notes.txt"), "not a fight");
        Files.writeString(dir.resolve("fight-abc.json"), "not a valid name");

        List<Path> files = store.list();

        assertEquals(List.of("fight-1700000003000.json", "fight-1700000002000.json", "fight-1700000001000.json"),
            files.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void aNameCollisionIsResolvedByAddingOneMillisecond(@TempDir Path dir) throws IOException {
        FightStore store = new FightStore(dir);

        Path first = store.save(fightAt(1_700_000_005_000L));
        Path second = store.save(fightAt(1_700_000_005_000L));
        Path third = store.save(fightAt(1_700_000_005_000L));

        assertEquals("fight-1700000005000.json", first.getFileName().toString());
        assertEquals("fight-1700000005001.json", second.getFileName().toString());
        assertEquals("fight-1700000005002.json", third.getFileName().toString());
        // the content saved under the bumped name is untouched: it still says it started at the original time
        assertEquals(1_700_000_005_000L, store.load(second).startedAt());
    }

    @Test
    void aFailedMoveLeavesNoTemporaryFileBehind(@TempDir Path dir) throws IOException {
        IOException refused = new IOException("the move was refused");
        FightStore store = new FightStore(dir, (from, to) -> {
            assertTrue(Files.exists(from), "the .tmp was written before the move");
            throw refused;
        });

        IOException thrown = assertThrows(IOException.class, () -> store.save(fightAt(1_700_000_007_000L)));

        assertSame(refused, thrown);
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void aCorruptFileThrowsAndIsLeftUntouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fight-1.json");
        Files.writeString(file, "{not json");

        assertThrows(IOException.class, () -> new FightStore(dir).load(file));
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void anEmptyFileThrowsAndIsLeftUntouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fight-1.json");
        Files.writeString(file, "");

        assertThrows(IOException.class, () -> new FightStore(dir).load(file));
        assertEquals("", Files.readString(file));
    }

    @Test
    void anUnsupportedSchemaThrowsAndIsLeftUntouched(@TempDir Path dir) throws IOException {
        // otherwise-valid content (round-trips fine at schema 1) so this fails only if the schema itself is not checked
        Path file = new FightStore(dir).save(fightAt(1_700_000_009_000L));
        String original = Files.readString(file);
        assertTrue(original.contains("\"schema\": 1"), original);
        String bumped = original.replace("\"schema\": 1", "\"schema\": 2");
        Files.writeString(file, bumped);

        assertThrows(IOException.class, () -> new FightStore(dir).load(file));
        assertEquals(bumped, Files.readString(file));
    }

    @Test
    void aMissingListThrowsAndIsLeftUntouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fight-1.json");
        String json = "{\"schema\": 1, \"addonVersion\": \"0.5.0\", \"outcome\": \"ENDED\", \"mode\": \"MANUAL\","
            + " \"self\": {}}";
        Files.writeString(file, json);

        assertThrows(IOException.class, () -> new FightStore(dir).load(file));
        assertEquals(json, Files.readString(file));
    }

    @Test
    void pruneDeletesEverythingPastTheFiftiethByFileNameOrderOnly(@TempDir Path dir) throws IOException {
        FightStore store = new FightStore(dir);
        long base = 1_700_000_000_000L;
        for (int i = 0; i < KEEP_PLUS_FIVE; i++) {
            store.save(fightAt(base + i * 1000L));
        }

        List<Path> removed = store.prune();

        assertEquals(5, removed.size());
        for (Path p : removed) assertFalse(Files.exists(p));
        List<Path> remaining = store.list();
        assertEquals(FightStore.KEEP, remaining.size());
        assertEquals("fight-" + (base + 54_000L) + ".json", remaining.get(0).getFileName().toString());
        assertEquals("fight-" + (base + 5_000L) + ".json", remaining.get(remaining.size() - 1).getFileName().toString());
    }

    @Test
    void noJsonKeyEverNamesAPosition(@TempDir Path dir) throws IOException {
        Path saved = new FightStore(dir).save(realisticFight());
        JsonElement root = JsonParser.parseString(Files.readString(saved));

        Set<String> keys = new HashSet<>();
        collectKeys(root, keys);

        for (String key : keys) {
            assertFalse(FORBIDDEN_KEY.matcher(key).matches(), "forbidden JSON key found: " + key);
        }
    }

    private static final int KEEP_PLUS_FIVE = FightStore.KEEP + 5;

    private static void collectKeys(JsonElement element, Set<String> keys) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> {
                keys.add(entry.getKey());
                collectKeys(entry.getValue(), keys);
            });
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectKeys(child, keys));
        }
    }

    private static FightRecord fightAt(long startedAt) {
        return new FightRecord(FightRecord.SCHEMA, "0.5.0", startedAt, startedAt + 1000, 1, FightOutcome.ENDED,
            false, FightMode.MANUAL, 0, List.of(), 0, emptyTotals(), List.of(), 0, List.of(), List.of(), List.of(),
            List.of());
    }

    private static SelfTotals emptyTotals() {
        return new SelfTotals(0, 0, 0, false, 0, 0, 0, 0, 0);
    }

    /** A fight with every list populated at least once, to exercise every JSON key the store writes. */
    private static FightRecord realisticFight() {
        long startedAt = 1_727_190_000_000L;
        long endedAt = 1_727_190_043_000L;

        List<Opponent> opponents = List.of(new Opponent("Foo", 2, false, 7, 31.5, 12));
        SelfTotals self = new SelfTotals(3, 8, 0, false, 96.0, 40, 38, 0, 55);
        List<DamageEvent> damage = List.of(
            new DamageEvent(412, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 20.0, 9.5, false),
            new DamageEvent(430, DamageKind.UNSEEN, AttackerKind.NONE, null, 9.5, 6.0, false));
        List<Sample> samples = List.of(
            new Sample(0, 36.0, 0.0, 8, true, 64, 64, 32, 4, 5.2, 1, true, false, true, 3, 3, 6),
            new Sample(1, 34.0, 2.0, 8, true, 63, 64, 32, 4, null, 0, false, false, true, 0, 0, 0));
        List<String> modulesAtStart = List.of("auto-totem", "crystal-aura", "surround");
        List<ModuleChange> moduleChanges = List.of(new ModuleChange(12, "hole-filler", true));
        List<PhaseChange> phases = List.of(new PhaseChange(0, CombatState.SURFACE, CombatPosture.CALM, "Foo"));

        return new FightRecord(FightRecord.SCHEMA, "0.5.0", startedAt, endedAt, 43, FightOutcome.LOST, false,
            FightMode.AUTO_PVP, 41, opponents, 2, self, damage, 0, samples, modulesAtStart, moduleChanges, phases);
    }
}
