package com.xploits.restock.core;

/**
 * The constants of unpacking a shulker box at the build (restock spec §3 "Shulkers at the build"), not settings; pinned
 * by {@code UnpackLimitsTest}. Ticks are client ticks (20 a second), counted only when no guard pauses, except the drain
 * limit, which counts every tick.
 *
 * @param placedTimeoutTicks from the place click to the box standing there, after which that cell failed
 * @param spotAttempts       cells tried before restock gives up setting the box down
 * @param toolSettleTicks    at least this many ticks between selecting the digging tool and the dig's START
 * @param settleLimitTicks   START waits at most this long for vanilla's breaking delta to equal restock's (P18)
 * @param digRestarts        digs started again (a delta or a slot changed, a box that did not go, and every let-go for
 *                           a pause, a move or a screen) before a stop
 * @param goneTimeoutTicks   from the dig's end to the cell being empty
 * @param pickUpWaitTicks    the drop is left to come to the player by itself this long before restock walks onto it
 * @param pickUpTimeoutTicks from the cell being empty to the box back in the inventory
 * @param stillWaitTicks     a step that needs the player still, no screen and the slot change allowed waits this long
 * @param drainLimitTicks    owner ruling R42: a stop waits this long, paused or not, for the break and the pick-up
 */
public record UnpackLimits(int placedTimeoutTicks, int spotAttempts, int toolSettleTicks, int settleLimitTicks,
                           int digRestarts, int goneTimeoutTicks, int pickUpWaitTicks, int pickUpTimeoutTicks,
                           int stillWaitTicks, int drainLimitTicks) {
    public static final UnpackLimits DEFAULTS = new UnpackLimits(40, 3, 4, 40, 3, 40, 20, 100, 100, 200);

    public UnpackLimits {
        positive(placedTimeoutTicks, "placedTimeoutTicks");
        positive(spotAttempts, "spotAttempts");
        if (toolSettleTicks < 0) throw new IllegalArgumentException("toolSettleTicks " + toolSettleTicks);
        if (settleLimitTicks <= toolSettleTicks) throw new IllegalArgumentException("settleLimitTicks " + settleLimitTicks);
        if (digRestarts < 0) throw new IllegalArgumentException("digRestarts " + digRestarts);
        positive(goneTimeoutTicks, "goneTimeoutTicks");
        positive(pickUpWaitTicks, "pickUpWaitTicks");
        if (pickUpTimeoutTicks <= pickUpWaitTicks) {
            throw new IllegalArgumentException("pickUpTimeoutTicks " + pickUpTimeoutTicks);
        }
        positive(stillWaitTicks, "stillWaitTicks");
        positive(drainLimitTicks, "drainLimitTicks");
    }

    private static void positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " " + value);
    }
}
