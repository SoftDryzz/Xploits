package com.xploits.stash.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashIndexTest {
    private static final ContainerKey A = ContainerKey.block("overworld", 0, 64, 0);
    private static final ContainerKey B = ContainerKey.block("overworld", 9, 64, 0);

    private static ContainerSnapshot chest(ContainerKey key, long seenAt, Map<String, Integer> items) {
        return new ContainerSnapshot(key, ContainerType.CHEST, seenAt, items, List.of());
    }

    @Test
    void reopeningTheSameContainerReplacesItsSnapshotInsteadOfAddingOne() {
        StashIndex index = new StashIndex();
        index.put(chest(A, 1000L, Map.of("minecraft:obsidian", 64)));
        index.put(chest(A, 2000L, Map.of("minecraft:obsidian", 128)));

        assertEquals(1, index.size());
        assertEquals(128, index.get(A).orElseThrow().totalOf("minecraft:obsidian"));
        assertEquals(2000L, index.get(A).orElseThrow().seenAt());
    }

    @Test
    void unknownContainerIsEmptyNotAnError() {
        assertTrue(new StashIndex().get(A).isEmpty());
    }

    @Test
    void findReturnsEveryContainerHoldingTheItem() {
        StashIndex index = new StashIndex();
        index.put(chest(A, 1000L, Map.of("minecraft:obsidian", 64)));
        index.put(chest(B, 1000L, Map.of("minecraft:obsidian", 320)));

        List<StashIndex.Hit> hits = index.find(Set.of("minecraft:obsidian"));

        assertEquals(2, hits.size());
        assertEquals(B, hits.get(0).key());
        assertEquals(320, hits.get(0).count());
    }

    @Test
    void findLooksInsideShulkersAndSaysWhichOne() {
        StashIndex index = new StashIndex();
        index.put(new ContainerSnapshot(A, ContainerType.CHEST, 1000L, Map.of(),
            List.of(new NestedShulker(3, "obby", "purple", Map.of("minecraft:obsidian", 1728)))));

        List<StashIndex.Hit> hits = index.find(Set.of("minecraft:obsidian"));

        assertEquals(1, hits.size());
        assertEquals(1728, hits.get(0).count());
        assertEquals("obby", hits.get(0).insideShulker());
    }

    @Test
    void anItemLooseInTheContainerReportsNoShulker() {
        StashIndex index = new StashIndex();
        index.put(chest(A, 1000L, Map.of("minecraft:obsidian", 64)));

        assertNull(index.find(Set.of("minecraft:obsidian")).get(0).insideShulker());
    }

    @Test
    void findWithNoMatchReturnsAnEmptyListNotNull() {
        StashIndex index = new StashIndex();
        index.put(chest(A, 1000L, Map.of("minecraft:obsidian", 64)));

        assertEquals(List.of(), index.find(Set.of("minecraft:diamond")));
    }

    @Test
    void totalShulkersOfAnEmptyIndexIsZero() {
        assertEquals(0, new StashIndex().totalShulkers());
    }

    @Test
    void totalShulkersOfAContainerWithNoShulkersIsZero() {
        StashIndex index = new StashIndex();
        index.put(chest(A, 1000L, Map.of("minecraft:obsidian", 64)));

        assertEquals(0, index.totalShulkers());
    }

    @Test
    void totalShulkersSumsAcrossSeveralContainers() {
        StashIndex index = new StashIndex();
        index.put(new ContainerSnapshot(A, ContainerType.CHEST, 1000L, Map.of(),
            List.of(new NestedShulker(0, "one", "purple", Map.of()))));
        index.put(new ContainerSnapshot(B, ContainerType.CHEST, 1000L, Map.of(),
            List.of(new NestedShulker(0, "two", "blue", Map.of()), new NestedShulker(1, "three", "red", Map.of()))));

        assertEquals(3, index.totalShulkers());
    }
}
