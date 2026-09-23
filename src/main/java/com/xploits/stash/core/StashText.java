package com.xploits.stash.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the stash-keeper module. Pure: ContainerKey uses it to say where a container is. */
public enum StashText implements MessageKey {
    MODULE_DESC,
    SETTING_NOTIFY,
    READ_FAILED,
    READ_FAILED_LOG,
    NOT_INDEXED,
    INDEXED,
    INDEXED_LOG,
    SAVE_FAILED,
    SAVE_FAILED_LOG,
    STATUS,
    NOW_CONTAINERS,
    WHERE_ENDER,
    WHERE_DIMENSION,
    WHERE_DISTANCE;

    @Override
    public String area() {
        return "stash";
    }
}
