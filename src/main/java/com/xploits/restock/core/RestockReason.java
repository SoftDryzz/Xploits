package com.xploits.restock.core;

import com.xploits.printer.core.Guards;

/**
 * Every reason restock refuses to start, stops or pauses (restock spec §3 "Guards", §5): each one has its catalog text
 * {@code restock.reason-<name>} saying what happened, what to do and which setting controls it. The printer's guards
 * ({@link Guards}) decide the shared ones; {@link #of(Guards.Reason)} maps them, exhaustively.
 */
public enum RestockReason {
    NO_WORLD(Effect.REFUSE), DEAD(Effect.REFUSE), CAMERA_NOT_PLAYER(Effect.REFUSE), RIDING(Effect.REFUSE),
    METEOR_API(Effect.REFUSE), NO_LITEMATICA(Effect.REFUSE), LITEMATICA_API(Effect.REFUSE),
    NO_PLACEMENT(Effect.REFUSE), PLACEMENT_DISABLED(Effect.REFUSE), NO_ENABLED_REGION(Effect.REFUSE),
    PLACEMENT_TOO_LARGE(Effect.REFUSE), PLACEMENT_OVERLAP(Effect.REFUSE), TRAVEL_RUNNING(Effect.REFUSE),
    SWEEP_RUNNING(Effect.REFUSE), NO_BARITONE(Effect.REFUSE), PREFIX_INVALID(Effect.REFUSE),
    BARITONE_SETTINGS_UNREADABLE(Effect.REFUSE), BARITONE_SETTINGS_NOT_READ(Effect.REFUSE),
    BARITONE_SAVED_UNREADABLE(Effect.REFUSE),
    BARITONE_SAVE_FAILED(Effect.REFUSE), LITEMATICA_PRINTER_UNREADABLE(Effect.REFUSE),
    EASY_PLACE_RESTRICTION(Effect.REFUSE),
    CONFLICTING_MODULE(Effect.STOP), PLAYER_NEAR(Effect.STOP), LOW_HEALTH(Effect.STOP), AUTO_PVP_ENGAGED(Effect.STOP),
    LEFT(Effect.STOP), DIED(Effect.STOP), DIMENSION(Effect.STOP), ATTACKED(Effect.STOP), SETBACK(Effect.STOP),
    CLICK_NOT_SENT(Effect.STOP), COMBAT_REPEATED(Effect.STOP), OTHER_ROTATION_REPEATED(Effect.STOP),
    BARITONE_NOT_LISTENING(Effect.STOP), MODULE_OFF(Effect.STOP), PLAYER_MOVED(Effect.STOP), NO_PATH(Effect.STOP),
    NO_PATH_BACK(Effect.STOP), CONTAINER_REFUSED(Effect.STOP), CONTAINER_CLOSED(Effect.STOP),
    NOTHING_FITS(Effect.STOP), INTERNAL(Effect.STOP),
    NO_HOTBAR_ROOM(Effect.STOP), BREAK_SPEED_MISMATCH(Effect.STOP), SHULKER_NOT_CARRIED(Effect.STOP),
    SHULKER_NO_SPOT(Effect.STOP), SHULKER_NOT_PLACED(Effect.STOP), SHULKER_NOT_BROKEN(Effect.STOP),
    SHULKER_NOT_PICKED_UP(Effect.STOP), UNPACK_BLOCKED(Effect.STOP),
    LAG(Effect.PAUSE), EATING(Effect.PAUSE), COMBAT(Effect.PAUSE), OTHER_ROTATION(Effect.PAUSE);

    public enum Effect { REFUSE, STOP, PAUSE }

    /** Why restock will not start; {@code detail} names modules or a setting when there are some, otherwise empty. */
    public record Refusal(RestockReason reason, String detail) {
    }

    private final Effect effect;

    RestockReason(Effect effect) {
        this.effect = effect;
    }

    public Effect effect() {
        return effect;
    }

    /** A printer guard's reason as restock says it; the printer-only ones cannot occur with restock's inputs. */
    public static RestockReason of(Guards.Reason r) {
        return switch (r) {
            case NO_WORLD -> NO_WORLD;
            case DEAD -> DEAD;
            case CAMERA_NOT_PLAYER -> CAMERA_NOT_PLAYER;
            case RIDING -> RIDING;
            case METEOR_API -> METEOR_API;
            case NO_LITEMATICA -> NO_LITEMATICA;
            case LITEMATICA_API -> LITEMATICA_API;
            case NO_PLACEMENT -> NO_PLACEMENT;
            case PLACEMENT_DISABLED -> PLACEMENT_DISABLED;
            case NO_ENABLED_REGION -> NO_ENABLED_REGION;
            case PLACEMENT_TOO_LARGE -> PLACEMENT_TOO_LARGE;
            case PLACEMENT_OVERLAP -> PLACEMENT_OVERLAP;
            case TRAVEL_RUNNING -> TRAVEL_RUNNING;
            case SWEEP_RUNNING -> SWEEP_RUNNING;
            case PREFIX_INVALID -> PREFIX_INVALID;
            case BARITONE_SETTINGS_UNREADABLE -> BARITONE_SETTINGS_UNREADABLE;
            case BARITONE_SAVE_FAILED -> BARITONE_SAVE_FAILED;
            case LITEMATICA_PRINTER_UNREADABLE -> LITEMATICA_PRINTER_UNREADABLE;
            case EASY_PLACE_RESTRICTION -> EASY_PLACE_RESTRICTION;
            case CONFLICTING_MODULE -> CONFLICTING_MODULE;
            case PLAYER_NEAR -> PLAYER_NEAR;
            case LOW_HEALTH -> LOW_HEALTH;
            case AUTO_PVP_ENGAGED -> AUTO_PVP_ENGAGED;
            case LEFT -> LEFT;
            case DIED -> DIED;
            case DIMENSION -> DIMENSION;
            case ATTACKED -> ATTACKED;
            case SETBACK -> SETBACK;
            case PLACEMENT_NOT_SENT -> CLICK_NOT_SENT;
            case COMBAT_REPEATED -> COMBAT_REPEATED;
            case OTHER_ROTATION_REPEATED -> OTHER_ROTATION_REPEATED;
            case BARITONE_NOT_LISTENING -> BARITONE_NOT_LISTENING;
            case MODULE_OFF -> MODULE_OFF;
            case LAG -> LAG;
            case EATING -> EATING;
            case COMBAT -> COMBAT;
            case OTHER_ROTATION -> OTHER_ROTATION;
            case BREAK_SPEED_MISMATCH -> BREAK_SPEED_MISMATCH;
            case NO_HOTBAR_ROOM -> NO_HOTBAR_ROOM;
            case LITEMATICA_PRINTER_ON, PLACEMENT_CHANGED, LAYER_RANGE_CHANGED, OTHER_PLACEMENT_OVERLAPS, LOOP,
                 CURSOR_NOT_EMPTY, FINISHED, LEFTOVERS, MATERIAL_MISSING, NOTHING_REACHABLE, NOTHING_KNOWN -> INTERNAL;
        };
    }

    public static Refusal of(Guards.Refusal r) {
        return new Refusal(of(r.reason()), r.detail());
    }
}
