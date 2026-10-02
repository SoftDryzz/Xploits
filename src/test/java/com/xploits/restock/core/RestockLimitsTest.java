package com.xploits.restock.core;

import com.xploits.printer.core.Guards;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock's constants, pinned: a change here is a decision, not a refactor. */
class RestockLimitsTest {
    @Test
    void theDefaults() {
        assertEquals(new RestockLimits(10, 100, 20, 64, 6.0, 200, 0.5, 4.5, 0.1, 2, 10), RestockLimits.DEFAULTS);
    }

    /** The defaults with one component replaced: the way to pin every check of the constructor on its own. */
    private static RestockLimits with(int component, Number value) {
        RestockLimits d = RestockLimits.DEFAULTS;
        Number[] v = {d.printerSettleTicks(), d.openTimeoutTicks(), d.contentWaitTicks(), d.maxTakeClicks(),
            d.approachRadius(), d.walkStallTicks(), d.walkProgress(), d.reach(), d.hitMargin(), d.duePasses(),
            d.leaveTicks()};
        v[component] = value;
        return new RestockLimits(v[0].intValue(), v[1].intValue(), v[2].intValue(), v[3].intValue(), v[4].doubleValue(),
            v[5].intValue(), v[6].doubleValue(), v[7].doubleValue(), v[8].doubleValue(), v[9].intValue(), v[10].intValue());
    }

    private static void refused(int component, Number value) {
        assertThrows(IllegalArgumentException.class, () -> with(component, value), "component " + component + " = " + value);
    }

    @Test
    void theSettleTicksMayBeZeroButNeverNegative() {
        with(0, 0);
        refused(0, -1);
    }

    @Test
    void everyCountOfTicksOrClicksIsPositive() {
        for (int component : new int[] {1, 2, 3, 5, 9, 10}) {
            with(component, 1);
            refused(component, 0);
            refused(component, -1);
        }
    }

    @Test
    void theApproachRadiusIsPositive() {
        with(4, 0.1);
        refused(4, 0.0);
        refused(4, -1.0);
        refused(4, Double.NaN);
    }

    @Test
    void theWalkProgressMayBeZeroButNeverNegative() {
        with(6, 0.0);
        refused(6, -0.1);
        refused(6, Double.NaN);
    }

    @Test
    void theReachIsPositiveAndAtMostTheGameOnes() {
        with(7, 4.5);
        with(7, 0.1);
        refused(7, 4.6);
        refused(7, 0.0);
        refused(7, -1.0);
        refused(7, Double.NaN);
    }

    @Test
    void theHitMarginIsPositiveAndBelowHalfAFace() {
        with(8, 0.49);
        refused(8, 0.5);
        refused(8, 0.0);
        refused(8, -0.1);
        refused(8, Double.NaN);
    }

    @Test
    void theConflictingModulesAreVerifiedNamesThatActByThemselves() {
        // The printer's verified list without the five that only dig; speed-mine is in (ruling R39): its Haste mode fakes
        // Haste on the client, so restock's own dig of a shulker box would stop early, and its grim bypass sends an ABORT
        // after every STOP. A name dropped (or added) is a decision.
        Set<String> expected = new HashSet<>(Guards.CONFLICTING_MODULES);
        List<String> digOnly = List.of("instant-rebreak", "packet-mine", "auto-tool", "vein-miner", "no-ghost-blocks");
        assertTrue(expected.containsAll(digOnly));
        expected.removeAll(digOnly);
        assertEquals(15, expected.size());
        assertEquals(expected, new HashSet<>(RestockLimits.CONFLICTING_MODULES));
        assertEquals(expected.size(), RestockLimits.CONFLICTING_MODULES.size(), "no name twice");
        assertTrue(RestockLimits.CONFLICTING_MODULES.contains("speed-mine"), "ruling R39");
        assertTrue(RestockLimits.CONFLICTING_MODULES.containsAll(List.of("scaffold", "air-place", "auto-walk",
            "anti-afk", "inventory-tweaks", "auto-replenish", "nuker")), "the seven the spec names");
    }
}
