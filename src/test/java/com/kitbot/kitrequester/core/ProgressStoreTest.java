package com.kitbot.kitrequester.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreTest {
    @TempDir
    Path dir;

    @Test
    void missingFileGivesEmptyProgress() throws IOException {
        Progress p = new ProgressStore(dir.resolve("progress.json")).load();
        assertTrue(p.delivered.isEmpty());
        assertNull(p.activeOrder);
        assertEquals(0, p.nextOrderAt);
    }

    @Test
    void roundTripKeepsEverything() throws IOException {
        Progress p = new Progress();
        p.delivered.addAll(List.of(1, 2));
        p.partial.add(List.of(3, 4));
        p.notFound.add(5);
        p.unconfirmed.add(List.of(6));
        p.skipped.add(7);
        p.failures.put(8, 2);
        p.activeOrder = new Progress.ActiveOrder(List.of(9, 10), 1234L, "StormAegis44");
        p.nextOrderAt = 5678L;

        Path file = dir.resolve("progress.json");
        new ProgressStore(file).save(p);
        Progress back = new ProgressStore(file).load();

        assertEquals(Set.of(1, 2), back.delivered);
        assertEquals(List.of(List.of(3, 4)), back.partial);
        assertEquals(Set.of(5), back.notFound);
        assertEquals(List.of(List.of(6)), back.unconfirmed);
        assertEquals(Set.of(7), back.skipped);
        assertEquals(Map.of(8, 2), back.failures);
        assertEquals(new Progress.ActiveOrder(List.of(9, 10), 1234L, "StormAegis44"), back.activeOrder);
        assertEquals(5678L, back.nextOrderAt);
    }

    @Test
    void corruptFileThrowsAndIsNotTouched() throws IOException {
        Path file = dir.resolve("progress.json");
        Files.writeString(file, "{not json");
        assertThrows(IOException.class, () -> new ProgressStore(file).load());
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void emptyFileThrows() throws IOException {
        Path file = dir.resolve("progress.json");
        Files.writeString(file, "");
        assertThrows(IOException.class, () -> new ProgressStore(file).load());
    }

    @Test
    void saveCreatesDirectoriesAndLeavesNoTmp() throws IOException {
        Path file = dir.resolve("a/b/progress.json");
        new ProgressStore(file).save(new Progress());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling("progress.json.tmp")));
    }

    @Test
    void trailingCommaInUnconfirmedIsSanitized() throws IOException {
        Path file = dir.resolve("progress.json");
        Files.writeString(file, "{\"unconfirmed\": [[1,2,3,4,5], ]}");
        Progress p = new ProgressStore(file).load();
        assertFalse(p.unconfirmed.contains(null));
        assertEquals(Set.of(1, 2, 3, 4, 5), p.resolved());
    }

    @Test
    void nullDeliveredBecomesEmptySet() throws IOException {
        Path file = dir.resolve("progress.json");
        Files.writeString(file, "{\"delivered\": null}");
        Progress p = new ProgressStore(file).load();
        assertTrue(p.delivered.isEmpty());
    }

    @Test
    void activeOrderWithoutIdsThrowsAndLeavesFileUntouched() throws IOException {
        Path file = dir.resolve("progress.json");
        String json = "{\"activeOrder\": {\"placedAt\": 123, \"courier\": \"X\"}}";
        Files.writeString(file, json);
        assertThrows(IOException.class, () -> new ProgressStore(file).load());
        assertEquals(json, Files.readString(file));
    }

    @Test
    void resolvedUnionsEveryBucketButNotFailuresOrActiveOrder() {
        Progress p = new Progress();
        p.delivered.add(1);
        p.partial.add(List.of(2, 3));
        p.notFound.add(4);
        p.unconfirmed.add(List.of(5));
        p.skipped.add(6);
        p.failures.put(8, 1);
        p.activeOrder = new Progress.ActiveOrder(List.of(9), 0L, null);
        assertEquals(Set.of(1, 2, 3, 4, 5, 6), p.resolved());
    }
}
