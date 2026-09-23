package com.xploits.console.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Game-side texts of the console: the module, its notices, the held-back marker and the history file. */
public enum ConsoleText implements MessageKey {
    MODULE_DESC,
    LEVEL_INFO,
    LEVEL_WARNING,
    LEVEL_ERROR,
    HISTORY_GAME,
    GAME_START,
    GAME_END,
    HISTORY_LOST,
    HISTORY_PRUNED,
    HELD,
    HELD_SHORT,
    BROKEN_FORMAT,
    CANNOT_OPEN,
    NOT_STARTED,
    CLOSED_FROM_MENU,
    CLOSED_BY_ERROR,
    WINDOW_CLOSED,
    NO_JAVA,
    NO_JAR,
    BAD_PATH_CHARACTER,
    IN_USE,
    CANNOT_PREPARE_LOG,
    FAILED,
    WINDOWS_REFUSED,
    UNEXPECTED_FAILURE,
    UNMARKED_COORDINATES,
    QUEUE_FULL,
    WRITER_FAILED,
    CANNOT_WRITE_LOG;

    @Override
    public String area() {
        return "console";
    }
}
