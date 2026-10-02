package com.xploits.restock.core;

import com.xploits.printer.core.Guards;
import com.xploits.printer.core.PrinterLimits;

import java.util.Optional;

/**
 * Owner ruling R42: what a stop does to a shulker box restock has out at the build. Attacked or low on health — and every
 * other stop where life, the world or the player's own hands come first: death, a dimension change, the server setting
 * the player back (rulings R45/R53: an anticheat flag — more digging and walking risks more flags or a kick), a fight
 * auto-pvp engaged, leaving, the module turned off (no tick follows), the player's movement keys, an internal fault —
 * stops at once: the box may stay, and the stop says where. Any other stop (a stranger near, a conflicting module,
 * another module that kept rotating…) first finishes the break and the pick-up, then stops. The guards latch their first
 * stop, so while it finishes the at-once reasons are read here from the guards' own inputs, in the guards' order.
 */
public final class UnpackStops {
    private UnpackStops() {
    }

    public static boolean finishesFirst(RestockReason r) {
        return switch (r) {
            case ATTACKED, LOW_HEALTH, SETBACK, AUTO_PVP_ENGAGED, DIED, DIMENSION, LEFT, NO_WORLD, MODULE_OFF,
                 PLAYER_MOVED, INTERNAL -> false;
            default -> r.effect() == RestockReason.Effect.STOP;
        };
    }

    /**
     * The first at-once stop the guards' inputs hold, in the guards' own order ({@code Guards.tick}: died, dimension,
     * auto-pvp, attacked, low health, a player near — not at once —, setback).
     */
    public static Optional<RestockReason> atOnce(Guards.Inputs in) {
        if (in.died()) return Optional.of(RestockReason.DIED);
        if (in.dimensionChanged()) return Optional.of(RestockReason.DIMENSION);
        if (in.autoPvpEngaged()) return Optional.of(RestockReason.AUTO_PVP_ENGAGED);
        if (in.attackedByPlayer()) return Optional.of(RestockReason.ATTACKED);
        if (!(in.health() >= in.minHealth())) return Optional.of(RestockReason.LOW_HEALTH);
        if (in.setback()) return Optional.of(RestockReason.SETBACK);
        return Optional.empty();
    }

    /** While finishing, a tick with no click: the server lags, the player eats or uses an item, someone else acts. */
    public static boolean holds(Guards.Inputs in, PrinterLimits limits) {
        return in.secondsSinceServerTick() >= limits.lagSeconds() || in.eatingOrUsing() || in.acting();
    }
}
