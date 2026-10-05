package com.xploits.restock.core;

import com.xploits.printer.core.PrinterLimits;

import java.util.List;

/**
 * The constants of restock's cores (restock spec §3), not settings; {@link #DEFAULTS} is the one the module uses and every
 * value is pinned by {@code RestockLimitsTest}. Ticks are client ticks (20 a second). The walk and aim values are the
 * printer's own ({@link PrinterLimits#DEFAULTS}), so restock walks and aims by the same rules.
 *
 * @param printerSettleTicks ticks between switching litematica-printer off and the first walking command
 * @param openTimeoutTicks   ticks from the click on a container to its screen, after which it does not open
 * @param contentWaitTicks   ticks a screen showing nothing is watched before it is trusted to be empty
 * @param maxTakeClicks      container clicks in one visit after which the take ends anyway
 * @param approachRadius     blocks from a stash-keeper container's column at which the approach ends
 * @param walkStallTicks     ticks without getting {@code walkProgress} closer before a walk has no path
 * @param walkProgress       blocks the distance must drop to count as progress
 * @param reach              eye to hit point, at most
 * @param hitMargin          the hit point stays this far from every edge of the clicked face
 * @param duePasses          full scan passes since a material ran out before it is due
 * @param leaveTicks         ticks in a row the player must stand ready to leave before a due trip starts
 */
public record RestockLimits(int printerSettleTicks, int openTimeoutTicks, int contentWaitTicks, int maxTakeClicks,
                            double approachRadius, int walkStallTicks, double walkProgress, double reach,
                            double hitMargin, int duePasses, int leaveTicks) {
    public static final RestockLimits DEFAULTS = new RestockLimits(10, 100, 20, 64, 6.0,
        PrinterLimits.DEFAULTS.walkStallTicks(), PrinterLimits.DEFAULTS.walkProgress(), PrinterLimits.DEFAULTS.maxReach(),
        PrinterLimits.DEFAULTS.hitMargin(), 2, 10);

    /**
     * Modules restock will not start beside, and stops for when one is turned on while it runs (spec §3 "Guards"): they
     * move, place, break or click by themselves while restock walks or opens a container. Names from the printer's
     * verified list ({@code Guards.CONFLICTING_MODULES}); the dig-only ones are left out — restock's dig posts no
     * {@code StartBreakingBlockEvent}, so they never touch its packet path — except {@code speed-mine} (ruling R39): its
     * Haste mode fakes Haste on the client, so restock's dig of a shulker box would stop early, and its grim bypass sends
     * an ABORT after every STOP.
     */
    public static final List<String> CONFLICTING_MODULES = List.of("anti-afk", "auto-walk", "auto-replenish",
        "inventory-tweaks", "scaffold", "air-place", "nuker", "highway-builder", "liquid-filler", "excavator",
        "infinity-miner", "echest-farmer", "spawn-proofer", "timer", "speed-mine");

    public RestockLimits {
        if (printerSettleTicks < 0) throw new IllegalArgumentException("printerSettleTicks " + printerSettleTicks);
        positive(openTimeoutTicks, "openTimeoutTicks");
        positive(contentWaitTicks, "contentWaitTicks");
        positive(maxTakeClicks, "maxTakeClicks");
        positive(walkStallTicks, "walkStallTicks");
        positive(duePasses, "duePasses");
        positive(leaveTicks, "leaveTicks");
        if (!(approachRadius > 0)) throw new IllegalArgumentException("approachRadius " + approachRadius);
        if (!(walkProgress >= 0)) throw new IllegalArgumentException("walkProgress " + walkProgress);
        if (!(reach > 0 && reach <= 4.5)) throw new IllegalArgumentException("reach " + reach);
        if (!(hitMargin > 0 && hitMargin < 0.5)) throw new IllegalArgumentException("hitMargin " + hitMargin);
    }

    private static void positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " " + value);
    }
}
