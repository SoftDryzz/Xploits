package com.xploits.stash.core;

import com.xploits.testing.TempFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashStoreTest {
    private static final ContainerKey KEY = ContainerKey.block("overworld", -1234, 63, 5678);

    private Path dir;

    @BeforeEach
    void createTempFolder() throws IOException {
        dir = TempFolder.create();
    }

    @AfterEach
    void deleteTempFolder() {
        TempFolder.delete(dir);
    }

    @Test
    void missingFileLoadsAnEmptyIndex() throws IOException {
        assertEquals(0, new StashStore(dir.resolve("index.json")).load().size());
    }

    @Test
    void theIndexSurvivesARestart() throws IOException {
        StashStore store = new StashStore(dir.resolve("index.json"));
        StashIndex index = new StashIndex();
        index.put(new ContainerSnapshot(KEY, ContainerType.CHEST, 1700000000000L,
            Map.of("minecraft:obsidian", 64),
            List.of(new NestedShulker(3, "obby", "purple", Map.of("minecraft:obsidian", 1728)))));
        store.save(index);

        StashIndex loaded = new StashStore(dir.resolve("index.json")).load();

        assertEquals(1, loaded.size());
        ContainerSnapshot snapshot = loaded.get(KEY).orElseThrow();
        assertEquals(1792, snapshot.totalOf("minecraft:obsidian"));
        assertEquals(1700000000000L, snapshot.seenAt());
        assertEquals("obby", snapshot.nested().get(0).identity());
        assertEquals(ContainerType.CHEST, snapshot.type());
    }

    @Test
    void theEnderChestSurvivesToo() throws IOException {
        StashStore store = new StashStore(dir.resolve("index.json"));
        StashIndex index = new StashIndex();
        index.put(new ContainerSnapshot(ContainerKey.ENDER, ContainerType.ENDER_CHEST, 1L,
            Map.of("minecraft:diamond", 5), List.of()));
        store.save(index);

        assertTrue(new StashStore(dir.resolve("index.json")).load().get(ContainerKey.ENDER).isPresent());
    }

    @Test
    void aCorruptFileIsReportedAndLeftUntouched() throws IOException {
        Path file = dir.resolve("index.json");
        Files.writeString(file, "{ this is not json");

        assertThrows(IOException.class, () -> new StashStore(file).load());
        assertEquals("{ this is not json", Files.readString(file));
    }

    @Test
    void aSyntacticallyValidFileWithAnUnknownContainerTypeIsReportedAndLeftUntouched() throws IOException {
        Path file = dir.resolve("index.json");
        String json = """
            {"containers":[{"dimension":"overworld","x":0,"y":64,"z":0,"ender":false,"type":"FOO","seenAt":1,"items":{},"nested":[]}]}""";
        Files.writeString(file, json);

        assertThrows(IOException.class, () -> new StashStore(file).load());
        assertEquals(json, Files.readString(file));
    }

    @Test
    void savingLeavesNoTempFileBehind() throws IOException {
        StashStore store = new StashStore(dir.resolve("index.json"));
        store.save(new StashIndex());

        try (var entries = Files.list(dir)) {
            assertEquals(List.of("index.json"), entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
}
