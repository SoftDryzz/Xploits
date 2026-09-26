package com.xploits.pvp.crystal.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The targets' hurt windows, as far as they tell whether a new crystal would be wasted (research-external M1-M3,
 * 3a; 1.21.11 bytecode). After a full hit the server ignores, for 10 ticks, any hit whose raw damage (before
 * armour) is not above the last one, and applies only the difference of a bigger one. The client learns of a full
 * hit, and of nothing else, from the damage packet, which names the entity that dealt it directly; the server
 * never says how big it was. So only a full hit from one of our own crystals, whose raw damage we predicted,
 * opens a window here, and any other full hit on that target closes it: its size is unknown.
 *
 * <p>{@link CrystalBrain} feeds it the full hits, in pre-ticks, and asks it about each spot it would place on.
 * The ticks are the brain's pre-tick numbers; a hit read between two pre-ticks carries the earlier one, so the
 * ticks since it are never fewer than have passed.
 */
public final class TargetWindows {
    /** A full hit's window: the server compares hits with it while its cooldown is above 10 of its 20 ticks. */
    public static final int HURT_WINDOW_TICKS = 10;
    /** Slack added to the landing estimate, so that a landing counted inside the window surely is. */
    public static final int LANDING_SLACK_TICKS = 2;
    /** A spot's raw damage must stay this far under the hit's for the window to surely swallow it. */
    public static final double RAW_MARGIN = 0.5;

    /** The last full hit on each target. */
    private final Map<String, Hit> last = new HashMap<>();

    /**
     * A full hit on {@code target}, read at pre-tick {@code tick}. It replaces the one before.
     *
     * @param ourRaw the raw damage we predicted for our crystal that dealt it, against this target; empty when the
     *               hit was not from one of our crystals, or we had no prediction for it: then the target's window
     *               is unknown and nothing is held back for it
     */
    public void fullHit(String target, long tick, OptionalDouble ourRaw) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ourRaw, "raw");
        if (ourRaw.isPresent()) Damage.check(ourRaw.getAsDouble(), "raw damage of the hit");
        last.put(target, new Hit(tick, ourRaw));
    }

    /**
     * Whether the target's window would surely swallow a crystal placed now that deals it {@code raw}: the last
     * full hit on it was ours ({@code raw1}), a crystal placed now lands surely inside the window
     * ({@link #landsInside}), and {@code raw <= raw1 - }{@link #RAW_MARGIN}.
     *
     * @param raw      the spot's exact raw damage to the target ({@link ExplosionMath#rawDamage})
     * @param now      this pre-tick
     * @param rttTicks your ping in ticks ({@link CrystalBrain#pingTicks})
     */
    public boolean swallows(String target, double raw, long now, int rttTicks) {
        Damage.check(raw, "raw damage");
        if (rttTicks < 0) throw new IllegalArgumentException("ping " + rttTicks);
        Hit hit = last.get(target);
        if (hit == null || hit.ourRaw.isEmpty()) return false;
        return landsInside(now - hit.tick, rttTicks) && raw <= hit.ourRaw.getAsDouble() - RAW_MARGIN;
    }

    /**
     * Whether a crystal placed now lands surely inside a window that opened {@code ticksSince} ticks ago. The hit
     * reached us half a round trip after the server dealt it; the placement takes half a round trip to the server,
     * the crystal half a round trip back to us, and our attack half a round trip to the server again: two round
     * trips, plus {@link #LANDING_SLACK_TICKS}, and the result still under {@link #HURT_WINDOW_TICKS}. With the ping
     * unknown ({@link CrystalBrain#UNKNOWN_PING_TICKS}, 5) two round trips are already the whole window, so it never
     * is.
     */
    public static boolean landsInside(long ticksSince, int rttTicks) {
        return ticksSince + 2L * rttTicks + LANDING_SLACK_TICKS < HURT_WINDOW_TICKS;
    }

    /** Forgets the hits whose window is over at pre-tick {@code now}; none of them could hold anything back. */
    public void expire(long now) {
        last.values().removeIf(hit -> now - hit.tick >= HURT_WINDOW_TICKS);
    }

    /** Whether a hit on this target is still remembered (tests). */
    boolean remembers(String target) {
        return last.containsKey(target);
    }

    private record Hit(long tick, OptionalDouble ourRaw) {}
}
