package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

/** A message's level, with its letter in {@code live.log} and its word in the history. */
public enum Level {
    INFO('I', ConsoleText.LEVEL_INFO),
    WARNING('W', ConsoleText.LEVEL_WARNING),
    ERROR('E', ConsoleText.LEVEL_ERROR);

    private final char code;
    private final ConsoleText label;

    Level(char code, ConsoleText label) {
        this.code = code;
        this.label = label;
    }

    public char code() {
        return code;
    }

    public String label(Catalog texts) {
        return texts.render(label);
    }

    public static Level fromCode(char code) {
        for (Level l : values()) {
            if (l.code == code) return l;
        }
        throw new IllegalArgumentException("unknown level: " + code);
    }
}
