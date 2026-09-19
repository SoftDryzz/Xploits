package com.xploits.pvp.core;

import java.util.List;

/**
 * Qué debería estar encendido este tick (spec §4).
 *
 * @param state cómo se informa la situación; puede ser SIN_RECURSOS aunque la fase física sea otra
 */
public record Plan(CombatState state, List<ManagedModule> enable, List<Skipped> skipped) {
    public Plan {
        enable = List.copyOf(enable);
        skipped = List.copyOf(skipped);
    }
}
