package com.xploits.bench;

import java.util.List;

/**
 * The bench's Litematica profile ({@code -Pbench.litematica}; restock spec §6): the CHECKs that need Litematica and
 * malilib loaded. {@code BenchTest} calls {@link #all()} only when Fabric says Litematica is there, so the default bench
 * never loads a class that names it.
 */
final class LitematicaScenarios {
    private LitematicaScenarios() {
    }

    static List<Scenario> all() {
        return List.of(new RestockLitematica());
    }
}
