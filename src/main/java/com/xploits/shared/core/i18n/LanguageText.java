package com.xploits.shared.core.i18n;

/** Texts of the language selector itself. */
public enum LanguageText implements MessageKey {
    MODULE_DESC,
    SETTING_LANGUAGE,
    SETTING_HIDE_COORDINATES_IN_LOG,
    ONLY_SETTINGS,
    NOW,
    NOW_RESTART,
    ALREADY,
    CURRENT,
    CURRENT_AUTO,
    NAME_ES,
    NAME_EN,
    FILE_UNREADABLE,
    FILE_UNWRITABLE,
    COMMAND_DESC;

    @Override
    public String area() {
        return "language";
    }
}
