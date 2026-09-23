package com.xploits.console.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Everything the console window prints. */
public enum WindowText implements MessageKey {
    ENVIRONMENT_ROW,
    FLIGHT_ROW,
    ELYTRA_NONE,
    TRAVEL_PROGRESS,
    SWEEP_PROGRESS,
    NO_TRAVEL,
    COMBAT_ROW,
    NO_MODULES,
    SECTION_LOGO,
    SECTION_ENVIRONMENT,
    SECTION_FLIGHT,
    SECTION_COMBAT,
    SECTION_MODULES,
    TOO_SMALL,
    NO_GAME_DATA,
    STATUS_FILTER,
    STATUS_PAUSED,
    STATUS_HIDDEN,
    HEARTBEAT_WAITING,
    HEARTBEAT_ALIVE,
    HEARTBEAT_SLOW,
    HEARTBEAT_NOT_RESPONDING,
    GAME_CLOSED,
    GAME_ENDED_WITHOUT_GOODBYE,
    MENU_LINE,
    FILTER_ALL,
    FILTER_PVP,
    FILTER_TRAVEL,
    FILTER_SWEEP,
    FILTER_WARNINGS,
    MENU_EMPTY,
    MENU_UNKNOWN,
    NOT_UTF8,
    CANNOT_READ_LOG,
    UNREADABLE_LINE,
    RECORDS_LOST,
    GAME_LOST_MESSAGES,
    PROBLEM,
    CRASHED,
    CRASH_DETAIL_AT,
    PRESS_ENTER,
    USAGE;

    @Override
    public String area() {
        return "window";
    }
}
