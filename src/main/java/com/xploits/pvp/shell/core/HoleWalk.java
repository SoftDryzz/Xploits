package com.xploits.pvp.shell.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The walk into a hole (surround++ spec §7.1): movement keys pressed a few ticks, as Meteor's AutoWalk's Simple mode does,
 * never Baritone. It stops the tick you press a key yourself, in a web, when the hole is lost or suddenly far (a teleport,
 * knockback), after {@link #TIMEOUT_TICKS}, or on arriving; stopped for any reason but arriving, it waits
 * {@link #COOLDOWN_TICKS} before another walk may start.
 */
public final class HoleWalk {
    public enum Key { FORWARD, BACK, LEFT, RIGHT }

    public static final int TIMEOUT_TICKS = 40;
    public static final int COOLDOWN_TICKS = 40;
    /** Within this of the hole's centre, horizontally, we are in. */
    public static final double ARRIVED = 0.15;
    /** A key is pressed when the way lies within 67.5 degrees of it (cos 67.5 = 0.38): two keys for a diagonal. */
    static final double KEY_THRESHOLD = 0.38;
    /** Further than this, the hole is not the one we set out for. */
    static final double LOST = Holes.MOVE_RADIUS + 1;

    /** This tick's keys, and whether the walk ended. */
    public record Step(Set<Key> keys, boolean stopped) {
        static final Step STOPPED = new Step(Set.of(), true);

        public Step {
            keys = Set.copyOf(keys);
        }
    }

    private boolean walking;
    private int ticks;
    private int cooldown;

    public boolean walking() {
        return walking;
    }

    /** No walk, and no wait after the last one. */
    public boolean ready() {
        return !walking && cooldown == 0;
    }

    /** A tick without a walk: the wait runs down. */
    public void idle() {
        if (cooldown > 0) cooldown--;
    }

    public void reset() {
        walking = false;
        ticks = 0;
        cooldown = 0;
    }

    /** Starts a walk; the keys for its first tick. {@code towards} is the hole's centre minus our feet. */
    public Set<Key> start(Vec towards, float yaw) {
        walking = true;
        ticks = 0;
        return keysFor(towards.x(), towards.z(), yaw);
    }

    public Step step(Optional<Vec> towards, boolean userKeys, boolean inWeb, float yaw) {
        if (!walking) throw new IllegalStateException("no walk under way");
        ticks++;
        if (towards.isEmpty() || userKeys || inWeb || ticks > TIMEOUT_TICKS) return stop(true);
        Vec t = towards.get();
        double distance = Math.hypot(t.x(), t.z());
        if (distance > LOST) return stop(true);
        if (distance <= ARRIVED) return stop(false);
        return new Step(keysFor(t.x(), t.z(), yaw), false);
    }

    private Step stop(boolean wait) {
        walking = false;
        cooldown = wait ? COOLDOWN_TICKS : 0;
        return Step.STOPPED;
    }

    /**
     * The keys that move us along {@code (dx, dz)} facing {@code yawDegrees}. Minecraft turns movement input into velocity
     * with forward = (-sin yaw, cos yaw) and left = (cos yaw, sin yaw) ({@code Entity.movementInputToVelocity}).
     */
    public static Set<Key> keysFor(double dx, double dz, float yawDegrees) {
        double length = Math.hypot(dx, dz);
        if (length == 0) return Set.of();
        double x = dx / length;
        double z = dz / length;
        double yaw = Math.toRadians(yawDegrees);
        double forward = -x * Math.sin(yaw) + z * Math.cos(yaw);
        double left = x * Math.cos(yaw) + z * Math.sin(yaw);
        EnumSet<Key> keys = EnumSet.noneOf(Key.class);
        if (forward > KEY_THRESHOLD) keys.add(Key.FORWARD);
        else if (forward < -KEY_THRESHOLD) keys.add(Key.BACK);
        if (left > KEY_THRESHOLD) keys.add(Key.LEFT);
        else if (left < -KEY_THRESHOLD) keys.add(Key.RIGHT);
        return Collections.unmodifiableSet(keys);
    }
}
