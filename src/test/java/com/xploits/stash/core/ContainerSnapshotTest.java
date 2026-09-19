package com.xploits.stash.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContainerSnapshotTest {
    private static final ContainerKey KEY = ContainerKey.block("overworld", 0, 64, 0);

    @Test
    void addsUpItemsInsideTheContainerAndInsideItsShulkers() {
        ContainerSnapshot snapshot = new ContainerSnapshot(KEY, ContainerType.CHEST, 1000L,
            Map.of("minecraft:obsidian", 64),
            List.of(new NestedShulker(3, "obby", "purple", Map.of("minecraft:obsidian", 1728))));

        assertEquals(1792, snapshot.totalOf("minecraft:obsidian"));
    }

    @Test
    void unknownItemIsZeroNotAnError() {
        ContainerSnapshot snapshot = new ContainerSnapshot(KEY, ContainerType.BARREL, 1000L, Map.of(), List.of());
        assertEquals(0, snapshot.totalOf("minecraft:diamond"));
    }

    @Test
    void namedShulkerUsesItsNameAsIdentity() {
        assertEquals("obby", new NestedShulker(3, "obby", "purple", Map.of()).identity());
    }

    @Test
    void unnamedShulkerFallsBackToItsSlot() {
        assertEquals("slot:3", new NestedShulker(3, null, "purple", Map.of()).identity());
    }

    @Test
    void theSnapshotCopiesItsCollectionsSoLaterChangesDoNotLeakIn() {
        Map<String, Integer> items = new HashMap<>();
        items.put("minecraft:obsidian", 64);
        ContainerSnapshot snapshot = new ContainerSnapshot(KEY, ContainerType.CHEST, 1000L, items, List.of());

        items.put("minecraft:obsidian", 999);

        assertEquals(64, snapshot.totalOf("minecraft:obsidian"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.items().put("x", 1));
    }
}
