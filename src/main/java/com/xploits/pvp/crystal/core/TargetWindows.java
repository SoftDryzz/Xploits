package com.xploits.pvp.crystal.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The targets' hurt windows, as far as they tell whether a new crystal would be wasted (research-external M1-M3,
 * 3a; 1.21.11 bytecode). After a full hit the server ignores, for 10 ticks, any hit whose raw damage (before
 * armour) is not above the last one, and applies only the difference of a bigger one. The client learns of a full
 * hit, and of nothing else, from the damage packet, which names the entity that dealt it directly; the server
 * never says how big it was. So only a full hit from one of our own crystals, whose raw damage we predicted,
 * opens a window here, and any other full hit on that target closes it: its size is unknown.
 *
 * <p>Whether a crystal placed now lands inside a window is learned from our own crystals (research-offense-gap:
 * the fastest landing is not a bound). {@link CrystalBrain} hands over each landing: the pre-ticks from the one
 * that first decided to place a crystal of ours to the first one that found it gone. The hit's packet and the
 * crystal's removal both reach us over the same connection, so the network's delay is inside that count and no
 * separate ping term is added to it — on the assumption that both are noticed the same tick they arrive, with no
 * extra, uneven delay between the two kinds of packet. That is what the in-game bench's simulated ping measures
 * (a fixed, symmetric delay); a real connection's jitter or per-packet handling could notice one kind later than
 * the other and is not covered. A crystal placed now lands, as we see it, the slowest recent landing later; it is
 * inside the window only while that is under {@link #HURT_WINDOW_TICKS} from the hit, with {@link #LANDING_MARGIN}
 * to spare. With fewer than {@link #LANDING_MIN_SAMPLES} landings known, nothing lands surely inside.
 *
 * <p>{@link CrystalBrain} feeds it the full hits, in pre-ticks, and asks it about each spot it would place on.
 * The ticks are the brain's pre-tick numbers; a hit read between two pre-ticks carries the earlier one, which is
 * never later than it arrived. That makes the ticks since it never fewer than have passed only while the pre-ticks
 * follow the client's ticks one to one: when the adapter skips pre-ticks (a tick it cannot measure), the ticks since
 * a hit, or since a placement, would come out short, so it forgets every window and every landing then
 * ({@link #clear}, through {@link CrystalBrain#forgetWindows}), and a hit read during the gap counts from the last
 * pre-tick before it.
 */
public final class TargetWindows {
    /** A full hit's window: the server compares hits with it while its cooldown is above 10 of its 20 ticks. */
    public static final int HURT_WINDOW_TICKS = 10;
    /** A spot's raw damage must stay this far under the hit's for the window to surely swallow it. */
    public static final double RAW_MARGIN = 0.5;
    /** The landings the bound is the slowest of: the last this many. */
    public static final int LANDING_SAMPLES = 20;
    /** With fewer landings than this known, no crystal lands surely inside a window. */
    public static final int LANDING_MIN_SAMPLES = 5;
    /** Ticks added to the slowest landing, so that a landing counted inside the window surely is. */
    public static final int LANDING_MARGIN = 1;
    /** A landing seen more than this many ticks ago no longer counts. */
    public static final int LANDING_SAMPLE_MAX_AGE = 200;
    /** A landing slower than this is an outlier: it is left out, not counted as this. */
    public static final int LANDING_SAMPLE_MAX_TICKS = 20;

    /** The last full hit on each target. */
    private final Map<String, Hit> last = new HashMap<>();
    /** Our crystals' latest landings, oldest first, at most {@link #LANDING_SAMPLES}. */
    private final ArrayDeque<Landing> landings = new ArrayDeque<>();

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
     * One of our crystals, first seen gone at pre-tick {@code at}, {@code ticks} pre-ticks after the one that first
     * decided to place it. A landing slower than {@link #LANDING_SAMPLE_MAX_TICKS} is left out; past
     * {@link #LANDING_SAMPLES} the oldest is forgotten.
     */
    public void landed(long at, long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("landing " + ticks);
        if (ticks > LANDING_SAMPLE_MAX_TICKS) return;
        landings.addLast(new Landing(at, (int) ticks));
        if (landings.size() > LANDING_SAMPLES) landings.removeFirst();
    }

    /**
     * Whether the target's window would surely swallow a crystal placed now that deals it {@code raw}: the last
     * full hit on it was ours ({@code raw1}), a crystal placed now lands surely inside the window
     * ({@link #landsInside}), and {@code raw <= raw1 - }{@link #RAW_MARGIN}.
     *
     * @param raw the spot's exact raw damage to the target ({@link ExplosionMath#rawDamage})
     * @param now this pre-tick
     */
    public boolean swallows(String target, double raw, long now) {
        Damage.check(raw, "raw damage");
        Hit hit = last.get(target);
        if (hit == null || hit.ourRaw.isEmpty()) return false;
        return landsInside(now - hit.tick, now) && raw <= hit.ourRaw.getAsDouble() - RAW_MARGIN;
    }

    /**
     * Whether a crystal placed at pre-tick {@code now} lands surely inside a window that opened {@code ticksSince}
     * ticks ago: at least {@link #LANDING_MIN_SAMPLES} landings known, and
     * {@code ticksSince + slowest + }{@link #LANDING_MARGIN}{@code  < }{@link #HURT_WINDOW_TICKS}, where
     * {@code slowest} is the slowest of the last {@link #LANDING_SAMPLES} landings not older than
     * {@link #LANDING_SAMPLE_MAX_AGE}.
     */
    public boolean landsInside(long ticksSince, long now) {
        OptionalInt slowest = slowestLanding(now);
        return slowest.isPresent() && ticksSince + slowest.getAsInt() + LANDING_MARGIN < HURT_WINDOW_TICKS;
    }

    /**
     * The slowest of the last {@link #LANDING_SAMPLES} landings not older than {@link #LANDING_SAMPLE_MAX_AGE} at
     * pre-tick {@code now}; empty when fewer than {@link #LANDING_MIN_SAMPLES} of them are.
     */
    OptionalInt slowestLanding(long now) {
        int count = 0;
        int slowest = 0;
        for (Landing l : landings) {
            if (now - l.at > LANDING_SAMPLE_MAX_AGE) continue;
            count++;
            slowest = Math.max(slowest, l.ticks);
        }
        return count < LANDING_MIN_SAMPLES ? OptionalInt.empty() : OptionalInt.of(slowest);
    }

    /**
     * Forgets the hits whose window is over at pre-tick {@code now}, none of which could hold anything back, and the
     * landings too old to count.
     */
    public void expire(long now) {
        last.values().removeIf(hit -> now - hit.tick >= HURT_WINDOW_TICKS);
        landings.removeIf(l -> now - l.at > LANDING_SAMPLE_MAX_AGE);
    }

    /**
     * Forgets every hit and every landing: after a gap in the pre-ticks, or while the budget is off, neither the
     * windows nor the time a crystal takes are known.
     */
    public void clear() {
        last.clear();
        landings.clear();
    }

    /** Whether a hit on this target is still remembered (tests). */
    boolean remembers(String target) {
        return last.containsKey(target);
    }

    private record Hit(long tick, OptionalDouble ourRaw) {}

    /** A landing of {@code ticks}, seen at pre-tick {@code at}. */
    private record Landing(long at, int ticks) {}
}
