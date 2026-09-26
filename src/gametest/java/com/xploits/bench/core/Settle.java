package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.CrystalBrain;

import java.util.Objects;

/**
 * Whether a measured crystal-aura run has settled (crystal-aura++ R3-6): nothing can happen in it any more, so
 * it may end before its nominal length with the same numbers it would have had at full length. It is fed one
 * {@link Observation} at T0 (the baseline) and one after every tick from then on, and says after each one
 * whether the run has settled. A run settles only when all of these hold:
 * <ol>
 *   <li>the scenario has natural regeneration off, and so does the world's {@code natural_health_regeneration}
 *   rule, read at T0 (the two must agree, or the run is wrong);</li>
 *   <li>the sparring's script is static (it never moves);</li>
 *   <li>for the last {@value #SETTLE_TICKS} consecutive ticks no end crystal existed, the aura under test sent
 *   no placement and no attack, our health plus absorption did not change, the sparring's health plus
 *   absorption did not change, the sparring did not pop, and no timer of the aura under test that could still
 *   fire was {@value #SETTLE_TICKS} ticks or longer;</li>
 *   <li>at least {@value #SETTLE_TICKS} ticks have passed since T0.</li>
 * </ol>
 * A tick that breaks condition 3 starts the count again from zero. Pure and deterministic.
 *
 * <p><b>The assumption the rule rests on:</b> every timer of the aura under test is shorter than
 * {@value #SETTLE_TICKS} ticks, so after that many quiet ticks nothing it started can still fire.
 * <ul>
 *   <li>Meteor's CrystalAura runs with every setting reset to its default for each run (only {@code pause-on-lag}
 *   is turned off): place, break, switch and support delays of 0 or 1 tick, and internal timers of at most 20
 *   ticks. They are fixed, so the bench feeds 0 for them.</li>
 *   <li>crystal-aura++'s longest is a pending placement's lifetime, max(5, ping + 2) ticks
 *   ({@link #plusPlusLongestTimer}), and the ping is not bounded: the bench feeds it every tick as
 *   {@link Observation#auraTimerTicks}, and a tick where it is {@value #SETTLE_TICKS} or more is never quiet.
 *   Its other windows are fixed and at most 20 ticks.</li>
 * </ul>
 * A setting that gave either aura a delay of {@value #SETTLE_TICKS} ticks or more would break the rule.
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
     * @param auraTimerTicks the longest timer of the aura under test that could still fire, in ticks: 0 for
     *                       Meteor's (fixed and short), {@link #plusPlusLongestTimer} for crystal-aura++'s
     */
    public record Observation(int crystals, int placementsSent, int attacksSent, double ourHealth,
                              double sparringHealth, int sparringPops, int auraTimerTicks) {
    }

    private final boolean possible;
    private Observation previous;
    /** Observations after the baseline. */
    private int ticks;
    /** Quiet ticks in a row, up to the last observation. */
    private int quiet;

    /**
     * @param naturalRegeneration whether the scenario runs with natural regeneration
     * @param worldRegeneration   whether the world's {@code natural_health_regeneration} rule is on, read at T0
     * @param staticScript        whether the sparring's script never moves it
     * @throws IllegalStateException when the scenario and the world disagree about regeneration
     */
    public Settle(boolean naturalRegeneration, boolean worldRegeneration, boolean staticScript) {
        if (naturalRegeneration != worldRegeneration) {
            throw new IllegalStateException("the scenario runs " + (naturalRegeneration ? "with" : "without")
                + " natural regeneration but the world has it " + (worldRegeneration ? "on" : "off"));
        }
        this.possible = !naturalRegeneration && !worldRegeneration && staticScript;
    }

    /**
     * crystal-aura++'s longest timer for a ping of {@code pingTicks}: a pending placement's lifetime,
     * max({@value CrystalBrain#PENDING_MIN_TICKS}, ping + {@value CrystalBrain#PENDING_PING_MARGIN}) ticks.
     */
    public static int plusPlusLongestTimer(int pingTicks) {
        if (pingTicks < 0) throw new IllegalArgumentException("ping " + pingTicks);
        return Math.max(CrystalBrain.PENDING_MIN_TICKS, pingTicks + CrystalBrain.PENDING_PING_MARGIN);
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
            && now.auraTimerTicks() < SETTLE_TICKS
            && now.placementsSent() == before.placementsSent()
            && now.attacksSent() == before.attacksSent()
            && Double.compare(now.ourHealth(), before.ourHealth()) == 0
            && Double.compare(now.sparringHealth(), before.sparringHealth()) == 0
            && now.sparringPops() == before.sparringPops();
    }
}
