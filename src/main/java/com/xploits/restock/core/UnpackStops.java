package com.xploits.restock.core;

import com.xploits.printer.core.Guards;
import com.xploits.printer.core.PrinterLimits;

import java.util.Objects;
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
    /** What the session does with the guards' stop while an unpack runs ({@link #onGuardStop}). */
    public sealed interface Answer permits Halt, Drain, Carry {
    }

    /**
     * The unpack stops now with the one action its stop allows; the session stops with {@code reason}. {@code also}:
     * the guards' own stop when an at-once reason read in the same tick halted the unpack instead (ruling R65), said as
     * one more line after acting.
     */
    public record Halt(RestockReason reason, Optional<RestockReason> also) implements Answer {
        public Halt {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(also, "also");
        }

        /** A halt with nothing more to say. */
        public Halt(RestockReason reason) {
            this(reason, Optional.empty());
        }
    }

    /** The unpack finishes the break and the pick-up first, then the session stops with {@code reason}. */
    public record Drain(RestockReason reason) implements Answer {
        public Drain {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The unpack goes on finishing for the stop it already drains for. */
    public record Carry() implements Answer {
    }

    private static final Carry CARRY = new Carry();

    private UnpackStops() {
    }

    /**
     * Owner ruling R42 for the guards' stop {@code stop} while an unpack runs. {@code outside}: a box is out
     * ({@link UnpackPlan#outside}); {@code draining}: the unpack already finishes for an earlier stop, which the guards
     * hold and give again every tick. Not finishing yet: a stop that finishes first, with a box out, finishes first —
     * unless an at-once reason holds in the same inputs (the guards name only their first stop of a tick, and a
     * setback after a stranger near is a one-tick pulse the next tick no longer shows; rulings R45/R53); anything else
     * halts now. The halt is named after the at-once reason when one holds (ruling R65: it is what halted it, and
     * nothing else would ever say a setback), the guards' own stop then going in {@link Halt#also}; else after the
     * guards' own stop. Finishing: the at-once reasons are read again from the inputs, in the guards' order; the first
     * halts now, with its own name (the stop it finished for has its own line, M7); none carries on.
     */
    public static Answer onGuardStop(RestockReason stop, boolean outside, boolean draining, Guards.Inputs in) {
        Objects.requireNonNull(stop, "stop");
        Optional<RestockReason> urgent = atOnce(in);
        if (draining) return urgent.<Answer>map(Halt::new).orElse(CARRY);
        if (finishesFirst(stop) && outside && urgent.isEmpty()) return new Drain(stop);
        RestockReason named = urgent.orElse(stop);
        return new Halt(named, named == stop ? Optional.empty() : Optional.of(stop));
    }

    public static boolean finishesFirst(RestockReason r) {
        // No default: a new reason is a compile error here until someone decides which side of the split it is on.
        return switch (r) {
            case ATTACKED, LOW_HEALTH, SETBACK, AUTO_PVP_ENGAGED, DIED, DIMENSION, LEFT, NO_WORLD, MODULE_OFF,
                 PLAYER_MOVED, INTERNAL -> false;
            case CONFLICTING_MODULE, PLAYER_NEAR, CLICK_NOT_SENT, COMBAT_REPEATED, OTHER_ROTATION_REPEATED,
                 BARITONE_NOT_LISTENING, NO_PATH, NO_PATH_BACK, CONTAINER_REFUSED, CONTAINER_CLOSED, NOTHING_FITS,
                 NO_HOTBAR_ROOM, BREAK_SPEED_MISMATCH, SHULKER_NOT_CARRIED, SHULKER_NO_SPOT, SHULKER_NOT_PLACED,
                 SHULKER_NOT_BROKEN, SHULKER_NOT_PICKED_UP, UNPACK_BLOCKED -> true;
            case DEAD, CAMERA_NOT_PLAYER, RIDING, METEOR_API, NO_LITEMATICA, LITEMATICA_API, NO_PLACEMENT,
                 PLACEMENT_DISABLED, NO_ENABLED_REGION, PLACEMENT_TOO_LARGE, PLACEMENT_OVERLAP, TRAVEL_RUNNING,
                 SWEEP_RUNNING, NO_BARITONE, PREFIX_INVALID, BARITONE_SETTINGS_UNREADABLE,
                 BARITONE_SETTINGS_NOT_READ, BARITONE_SAVED_UNREADABLE, BARITONE_SAVE_FAILED,
                 LITEMATICA_PRINTER_UNREADABLE, EASY_PLACE_RESTRICTION, LAG, EATING, COMBAT, OTHER_ROTATION -> false;
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
