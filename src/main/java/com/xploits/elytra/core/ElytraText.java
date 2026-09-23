package com.xploits.elytra.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the elytra-replace module. */
public enum ElytraText implements MessageKey {
    MODULE_DESC,
    SETTING_SWAP_BELOW,
    SETTING_MIN_SPARE,
    SETTING_NOTIFY,
    SETTING_NOTIFY_SOUND,
    SWAPPED,
    SWAP_NOT_TAKING,
    NO_SPARE_ABOVE_MINIMUM,
    NO_SPARE_BETTER;

    @Override
    public String area() {
        return "elytra";
    }
}
