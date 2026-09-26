package com.xploits.bench.core;

import java.util.Objects;

/**
 * Whether a measured crystal-aura run has settled (crystal-aura++ R3-6): nothing can happen in it any more, so
 * it may end before its nominal length with the same numbers it would have had at full length. It is fed one
 * {@link Observation} at T0 (the baseline) and one after every tick from then on, and says after each one
 * whether the run has settled. A run settles only when all of these hold:
 * <ol>
 *   <li>the scenario has natural regeneration off;</li>
 *   <li>the sparring's script is static (it never moves);</li>
 *   <li>for the last {@value #SETTLE_TICKS} consecutive ticks no end crystal existed, the aura under test sent
 *   no placement and no attack, our health plus absorption did not change, the sparring's health plus
 *   absorption did not change, and the sparring did not pop;</li>
 *   <li>at least {@value #SETTLE_TICKS} ticks have passed since T0.</li>
 * </ol>
 * A tick that breaks condition 3 starts the count again from zero. Pure and deterministic.
 */
public final class Settle {
    /** Quiet ticks in a row a run needs to settle, and the fewest ticks after T0 it may settle at. */
    public static final int SETTLE_TICKS = 60;

    /**
     * What the bench sees after one tick. The counters are running totals: only their changes count, so a
     * counter may count more than its name says (a busier run settles later, never wrongly), as long as it moves
     * with every placement or attack.
     *
     * @param crystals       end crystal entities in the world now
     * @param placementsSent placement packets sent so far
     * @param attacksSent    attack packets sent so far
     * @param ourHealth      our health plus absorption now
     * @param sparringHealth the sparring's health plus absorption now
     * @param sparringPops   the sparring's pops so far
     */
    public record Observation(int crystals, int placementsSent, int attacksSent, double ourHealth,
                              double sparringHealth, int sparringPops) {
    }

    private final boolean possible;
    private Observation previous;
    /** Observations after the baseline. */
    private int ticks;
    /** Quiet ticks in a row, up to the last observation. */
    private int quiet;

    /**
     * @param naturalRegeneration whether the scenario's world heals naturally
     * @param staticScript        whether the sparring's script never moves it
     */
    public Settle(boolean naturalRegeneration, boolean staticScript) {
        this.possible = !naturalRegeneration && staticScript;
    }

    /** Whether this run can settle at all; when it cannot, nothing needs observing. */
    public boolean possible() {
        return possible;
    }

    /**
     * Takes the next observation: the first one is the baseline at T0, each later one the state after one more
     * tick. Returns whether the run has settled.
     */
    public boolean observe(Observation now) {
        Objects.requireNonNull(now, "now");
        Observation before = previous;
        previous = now;
        if (before == null) return false;
        ticks++;
        quiet = quiet(before, now) ? quiet + 1 : 0;
        return possible && quiet >= SETTLE_TICKS && ticks >= SETTLE_TICKS;
    }

    /** Ticks observed after the baseline. */
    public int ticks() {
        return ticks;
    }

    private static boolean quiet(Observation before, Observation now) {
        return now.crystals() == 0
            && now.placementsSent() == before.placementsSent()
            && now.attacksSent() == before.attacksSent()
            && Double.compare(now.ourHealth(), before.ourHealth()) == 0
            && Double.compare(now.sparringHealth(), before.sparringHealth()) == 0
            && now.sparringPops() == before.sparringPops();
    }
}
