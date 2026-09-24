package com.xploits.shared.core.migration;

import com.xploits.shared.core.i18n.MessageKey;

/** Notices of the 0.4.0 settings migration. */
public enum MigrationText implements MessageKey {
    DONE,
    FAILED,
    CONSOLE_FOLDER_BUSY;

    @Override
    public String area() {
        return "migration";
    }
}
