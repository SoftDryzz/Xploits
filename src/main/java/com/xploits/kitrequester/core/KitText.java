package com.xploits.kitrequester.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the kit-requester module. Pure: OrderMachine and Action carry it too. */
public enum KitText implements MessageKey {
    MODULE_DESC,
    SETTING_INTERVAL_SECONDS,
    SETTING_KNOWN_COURIERS,
    SETTING_TRUST_UNKNOWN_COURIERS,
    SETTING_AUTO_ENDER,
    SETTING_NOTIFY_SOUND,
    NOTHING,
    DISABLED,
    LOAD_FAILED,
    LOAD_FAILED_LOG,
    SAVE_FAILED,
    SAVE_FAILED_LOG,
    RELOAD_WRONG_STATE,
    RELOAD_FAILED,
    RELOAD_DONE,
    SEND_FAILED_NO_PLAYER,
    STATUS,
    STATUS_COURIER,
    CONFIRM_TIMEOUT_NOTICE,
    FAIL_COURIER_LATE,
    FAIL_COURIER_CANCELLED,
    FAIL_NOTICE,
    DELIVERY_UNCONFIRMED,
    COOLDOWN_NOTICE,
    UNREGISTERED,
    USAGE_ERROR,
    UNKNOWN_KITBOT,
    TPA_IGNORED_NO_ORDER,
    UNKNOWN_COURIER_REJECTED,
    TPA_IGNORED,
    NEW_COURIER_LEARNED,
    DELIVERY_PARTIAL,
    DELIVERED,
    NOT_FOUND,
    QUEUE_FINISHED,
    SCREEN_OPEN_BLOCKING,
    INVENTORY_FULL_PAUSED,
    DEPOSIT_SAVED,
    DEPOSIT_NO_ROOM,
    DEPOSIT_ABORTS_EXCEEDED,
    STATE_IDLE,
    STATE_AWAIT_CONFIRM,
    STATE_AWAIT_COURIER,
    STATE_AWAIT_DELIVERY,
    STATE_DEPOSIT,
    STATE_PAUSED,
    STATE_FINISHED,
    STATE_ERROR;

    /** How a state of the order machine is named to the player. */
    public static KitText of(OrderMachine.State state) {
        return valueOf("STATE_" + state.name());
    }

    @Override
    public String area() {
        return "kits";
    }
}
