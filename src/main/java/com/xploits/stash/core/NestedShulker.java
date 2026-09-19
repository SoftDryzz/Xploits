package com.xploits.stash.core;

import java.util.Map;

/**
 * Un shulker dentro de un contenedor (spec §4.1). Guarda el slot que ocupaba porque es lo único
 * que permite reconocer a un shulker sin nombre; con nombre, el nombre manda (spec §4.3).
 *
 * @param customName nombre personalizado, o null si no tiene
 */
public record NestedShulker(int slot, String customName, String color, Map<String, Integer> items) {
    public NestedShulker {
        items = Map.copyOf(items);
    }

    /** Nombre si lo tiene; si no, su posición. Es la identidad con la que se le sigue la pista. */
    public String identity() {
        return customName != null && !customName.isBlank() ? customName : "slot:" + slot;
    }

    public int totalOf(String itemId) {
        return items.getOrDefault(itemId, 0);
    }
}
