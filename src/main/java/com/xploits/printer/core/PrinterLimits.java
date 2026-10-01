package com.xploits.printer.core;

/**
 * The constants of the cores (printer spec §9, N-I5): not settings; {@link #DEFAULTS} is the one the module uses and every
 * value is pinned by {@code PrinterLimitsTest}. Ticks are client ticks (20 a second).
 *
 * @param breakCapTicks     a wrong block slower than this with the best hotbar tool is skipped and reported (5 s)
 * @param durabilityFloor   a tool with fewer uses left is never used
 * @param hitMargin         the hit point stays this far from every edge of the clicked face
 * @param failedPlacements  K: placements sent without the server's update showing them before a position is skipped
 * @param yieldTicks        N_YIELD: consecutive ticks a combat module acts before the printer pauses
 * @param clearTicks        N_CLEAR: consecutive clear ticks that end that pause
 * @param pausesBeforeStop  combat pauses in one session that make the next one a stop
 * @param unreachableSpots  stand spots in a row the walk could not reach before "nothing reachable"
 * @param pendingMinTicks   the shortest pending window, whatever the measured round trip
 * @param rotationPriority  Meteor rotation priority, one step below the -100 holds (spike S6)
 * @param unknownGoals      {@code #goto x z} goals towards unloaded parts before "nothing known"
 * @param maxReach          eye to hit point, the most the {@code reach} setting may say
 * @param breakGapTicks     no START earlier than this many ticks after a STOP or an ABORT (vanilla: 6)
 * @param lagSeconds        server lag pause threshold ({@code TickRate.getTimeSinceLastTick()})
 * @param scanBudget        positions of the build re-read per tick
 * @param maxVolume         the largest build (sum of its boxes) the index takes
 * @param rayBudget         raycasts the planner may ask for per tick
 * @param walkStallTicks    ticks without getting {@code walkProgress} closer to a stand spot before it is unreachable
 * @param walkProgress      blocks the distance to a stand spot must drop to count as progress
 */
public record PrinterLimits(int breakCapTicks, int durabilityFloor, double hitMargin, int failedPlacements,
                            int yieldTicks, int clearTicks, int pausesBeforeStop, int unreachableSpots,
                            int pendingMinTicks, int rotationPriority, int unknownGoals, double maxReach,
                            int breakGapTicks, double lagSeconds, int scanBudget, long maxVolume, int rayBudget,
                            int walkStallTicks, double walkProgress) {
    public static final PrinterLimits DEFAULTS = new PrinterLimits(100, 10, 0.1, 3, 20, 40, 3, 3, 4, -101, 4, 4.5, 6,
        1.5, 16384, 4_194_304L, 64, 200, 0.5);

    public PrinterLimits {
        positive(breakCapTicks, "breakCapTicks");
        positive(durabilityFloor, "durabilityFloor");
        positive(failedPlacements, "failedPlacements");
        positive(yieldTicks, "yieldTicks");
        positive(clearTicks, "clearTicks");
        positive(pausesBeforeStop, "pausesBeforeStop");
        positive(unreachableSpots, "unreachableSpots");
        positive(pendingMinTicks, "pendingMinTicks");
        positive(unknownGoals, "unknownGoals");
        positive(breakGapTicks, "breakGapTicks");
        positive(scanBudget, "scanBudget");
        positive(rayBudget, "rayBudget");
        positive(walkStallTicks, "walkStallTicks");
        if (!(hitMargin > 0 && hitMargin < 0.5)) throw new IllegalArgumentException("hitMargin " + hitMargin);
        if (!(maxReach > 0 && maxReach <= 4.5)) throw new IllegalArgumentException("maxReach " + maxReach);
        if (rotationPriority >= -100) throw new IllegalArgumentException("rotationPriority " + rotationPriority);
        if (!(lagSeconds > 0)) throw new IllegalArgumentException("lagSeconds " + lagSeconds);
        if (maxVolume < 1) throw new IllegalArgumentException("maxVolume " + maxVolume);
        if (!(walkProgress >= 0)) throw new IllegalArgumentException("walkProgress " + walkProgress);
    }

    private static void positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " " + value);
    }
}
