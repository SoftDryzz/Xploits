package com.xploits.pvp.core;

import java.util.Map;

/**
 * La situación traducida a valores simples (spec §5). Es la frontera: de aquí en adelante todo el
 * criterio es lógica pura y se puede probar sin arrancar el juego.
 *
 * <p>La vida del objetivo no está aquí a propósito: ninguna transición la necesita y no todos los
 * servidores la mandan (spec §5.2). Tu vida tampoco: sería el dato para decidir la retirada, y la
 * huida automática está fuera de alcance.
 */
public record CombatSnapshot(boolean hasTarget, double targetDistance,
                             boolean targetSurrounded, boolean targetBurrowed, boolean targetGliding,
                             boolean selfGliding, int selfTotems,
                             Map<Resource, Integer> resources) {
    public CombatSnapshot {
        resources = Map.copyOf(resources);
    }

    /** Sin objetivo y sin nada encima. */
    public static CombatSnapshot none() {
        return new CombatSnapshot(false, 0, false, false, false, false, 0, Map.of());
    }

    public int amountOf(Resource resource) {
        return resources.getOrDefault(resource, 0);
    }
}
