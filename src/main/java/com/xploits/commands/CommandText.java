package com.xploits.commands;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the {@code .xploits} command itself (the module subcommands answer with their own). */
public enum CommandText implements MessageKey {
    MODULE_NOT_REGISTERED,
    STASH_OFF_STATUS,
    STASH_OFF_FIND,
    FIND_TOO_SHORT,
    FIND_UNKNOWN_ITEM,
    FIND_NOT_SEEN,
    FIND_HEADER_ONE,
    FIND_HEADER_MANY,
    FIND_HIT,
    FIND_IN_SHULKER,
    FIND_MORE,
    AGO_NOW,
    AGO_MINUTES,
    AGO_HOURS,
    AGO_DAY,
    AGO_DAYS;

    @Override
    public String area() {
        return "command";
    }
}
