package com.xploits.restock.core;

import com.xploits.printer.core.Guards;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock's constants, pinned: a change here is a decision, not a refactor. */
class RestockLimitsTest {
    @Test
    void theDefaults() {
        assertEquals(new RestockLimits(10, 100, 20, 64, 6.0, 200, 0.5, 4.5, 0.1, 2, 10), RestockLimits.DEFAULTS);
    }

    @Test
    void impossibleValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RestockLimits(-1, 100, 20, 64, 6.0, 200, 0.5, 4.5, 0.1, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> new RestockLimits(10, 0, 20, 64, 6.0, 200, 0.5, 4.5, 0.1, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> new RestockLimits(10, 100, 20, 64, 6.0, 200, 0.5, 4.6, 0.1, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> new RestockLimits(10, 100, 20, 64, 6.0, 200, 0.5, 4.5, 0.5, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> new RestockLimits(10, 100, 20, 64, 6.0, 200, 0.5, 4.5, 0.1, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new RestockLimits(10, 100, 20, 64, 6.0, 200, 0.5, 4.5, 0.1, 2, 0));
    }

    @Test
    void theConflictingModulesAreVerifiedNamesThatActByThemselves() {
        assertTrue(Guards.CONFLICTING_MODULES.containsAll(RestockLimits.CONFLICTING_MODULES));
        assertTrue(RestockLimits.CONFLICTING_MODULES.containsAll(List.of("scaffold", "air-place", "auto-walk",
            "anti-afk", "inventory-tweaks", "auto-replenish", "nuker")), "the seven the spec names");
        for (String digOnly : List.of("instant-rebreak", "speed-mine", "packet-mine", "auto-tool", "vein-miner",
            "no-ghost-blocks")) {
            assertFalse(RestockLimits.CONFLICTING_MODULES.contains(digOnly), digOnly);
        }
    }
}
