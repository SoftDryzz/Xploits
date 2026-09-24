package com.xploits.pvp.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * "The target is moving away steadily" (redesign §4.3), measured so that it does not oscillate.
 *
 * <p>It is needed because {@code auto-web} and {@code crystal-aura} step on each other: the aura
 * requires the block above the base to be air and {@code auto-web} webs exactly that cell, so with
 * both on at once the web takes the aura's best crystal position away in exchange for a web the other
 * one breaks by hand in half a second. The web is for <b>stopping them from leaving</b>, not for while
 * you hit them, and that is why "is leaving" has to be told apart from "has moved".
 *
 * <h2>How it is measured</h2>
 * Not by the speed of one tick -the noise swallows it- but by the <b>ground gained over a
 * window</b>: the distance now minus the one {@link #WINDOW_TICKS} ticks ago. That difference is
 * the net separation, which is exactly what matters: if you both run at the same speed, the other one
 * is not leaving however much they move.
 *
 * <p>And the result has a <b>dead band</b>, not a bare threshold: "moving away" is entered with
 * {@link #START_GAIN} blocks gained over the window and only left when it drops below
 * {@link #STOP_GAIN}. With a single threshold, a difference hovering around the exact value
 * would turn {@code auto-web} on and off on alternate ticks, which is the oscillation to
 * avoid; with two, it takes {@link #START_GAIN} - {@link #STOP_GAIN} = 0.75 blocks of change in
 * the difference for the result to flip, and neither the knockback of a hit nor
 * orbiting around the enemy produces that.
 */
public final class RetreatWatch {
    /**
     * Width of the window, in ticks. Half a second: the same unit of combat time §9 uses
     * ("half a second is 2-5 crystal cycles") and about one whole crystal cycle.
     * Shorter, the noise of a hit dominates the measurement; longer, the web arrives late for someone
     * who has already left.
     */
    public static final int WINDOW_TICKS = 10;

    /**
     * Blocks of separation gained over the window to declare that it is moving away. 1.0 block in half a
     * second is 2 blocks per second of net separation, little more than a third of sprint
     * speed (5.6 b/s): the knockback of a hit (about 0.4 blocks) and orbiting around the
     * enemy while you crystal them (±0.5) stay clearly below it, whereas whoever really leaves
     * reaches it -and so does whoever leaves you behind because you have been slowed down-.
     */
    public static final double START_GAIN = 1.0;

    /**
     * Blocks gained below which it stops being considered to be moving away. It is not zero so
     * that the end does not flicker either: as long as it keeps gaining some ground, it is still leaving.
     */
    public static final double STOP_GAIN = 0.25;

    private final Deque<Double> distances = new ArrayDeque<>();
    private String targetId;
    private boolean retreating;

    /**
     * Adds this tick's distance and returns whether the target is moving away steadily.
     *
     * <p>Losing the target or changing target clears the series: comparing the distance to one with the
     * distance to another would give a huge jump and a web for nobody. The change is detected by
     * {@link CombatSnapshot#targetId()}; while the adapter does not fill it in, two different targets
     * in a row share a series and the worst that can happen is that {@code auto-web} is
     * on too much for half a window, which is the cheap side of the §10 bias.
     */
    public boolean update(CombatSnapshot snapshot) {
        if (!snapshot.hasTarget()) {
            reset();
            return false;
        }
        if (!Objects.equals(targetId, snapshot.targetId())) {
            reset();
            targetId = snapshot.targetId();
        }

        distances.addLast(snapshot.targetDistance());
        while (distances.size() > WINDOW_TICKS + 1) distances.removeFirst();
        // Until the window is full there is nothing to compare: nobody just seen is claimed
        // to be moving away.
        if (distances.size() <= WINDOW_TICKS) return retreating;

        double gained = distances.getLast() - distances.getFirst();
        retreating = retreating ? gained > STOP_GAIN : gained >= START_GAIN;
        return retreating;
    }

    /** Whether the last call to {@link #update} concluded it is moving away. */
    public boolean retreating() {
        return retreating;
    }

    /** Forgets the whole series. */
    public void reset() {
        distances.clear();
        targetId = null;
        retreating = false;
    }
}
