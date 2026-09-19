package com.xploits.stash.core;

import java.util.List;
import java.util.Map;

/**
 * Una foto del contenido de un contenedor (spec §4.1). Los ítems se agregan por tipo, no slot a slot:
 * ninguna función del módulo necesita saber en qué hueco estaba cada pila.
 *
 * @param seenAt epoch millis de cuando se tomó
 */
public record ContainerSnapshot(ContainerKey key, ContainerType type, long seenAt,
                                Map<String, Integer> items, List<NestedShulker> nested) {
    public ContainerSnapshot {
        items = Map.copyOf(items);
        nested = List.copyOf(nested);
    }

    /** Cuántas unidades de ese ítem hay, contando también las que están dentro de shulkers. */
    public int totalOf(String itemId) {
        int total = items.getOrDefault(itemId, 0);
        for (NestedShulker shulker : nested) total += shulker.totalOf(itemId);
        return total;
    }
}
