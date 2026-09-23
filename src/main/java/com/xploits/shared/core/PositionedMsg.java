package com.xploits.shared.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.Objects;

/**
 * A player message in two halves: the chat half may carry coordinates, the log half
 * (console window and its files) never does. Marked at the source, which is the only place that
 * knows what the text carries.
 */
public record PositionedMsg(Msg chat, Msg log) {
    public PositionedMsg {
        Objects.requireNonNull(chat, "chat message");
        Objects.requireNonNull(log, "log message");
    }

    public static PositionedMsg same(Msg msg) {
        return new PositionedMsg(msg, msg);
    }
}
