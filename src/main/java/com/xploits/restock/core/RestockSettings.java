package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;

/** restock's settings as the cores read them each tick, with their bounds (spec §5). {@code mark-key} is the adapter's. */
public record RestockSettings(int maxDistance, boolean useStashKeeper, BaritoneSession.Mode baritoneSettings,
                              String baritonePrefix, boolean stopNearPlayers, int playerDistance, double minHealth) {
    public static final int MIN_DISTANCE = 8;
    public static final int MAX_DISTANCE = 256;
    public static final double MIN_HEALTH = 1;
    public static final double MAX_HEALTH = 36;
    public static final RestockSettings DEFAULTS = new RestockSettings(64, true, BaritoneSession.Mode.MINE, "#", true,
        48, 10);

    public RestockSettings {
        if (maxDistance < MIN_DISTANCE || maxDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException("max-distance " + maxDistance);
        }
        if (playerDistance < MIN_DISTANCE || playerDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException("player-distance " + playerDistance);
        }
        if (!(minHealth >= MIN_HEALTH && minHealth <= MAX_HEALTH)) throw new IllegalArgumentException("min-health " + minHealth);
        if (baritonePrefix == null || baritoneSettings == null) throw new IllegalArgumentException("a setting is missing");
    }
}
