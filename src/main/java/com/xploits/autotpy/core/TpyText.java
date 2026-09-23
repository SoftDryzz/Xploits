package com.xploits.autotpy.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the auto-tpy module. */
public enum TpyText implements MessageKey {
    MODULE_DESC,
    SETTING_USERS,
    SETTING_INCLUDE_FRIENDS,
    SETTING_NOTIFY,
    ACCEPTED,
    IGNORED,
    NOW_LISTED;

    @Override
    public String area() {
        return "tpy";
    }
}
