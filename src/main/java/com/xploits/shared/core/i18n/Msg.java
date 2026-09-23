package com.xploits.shared.core.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Something to tell the player, not yet in any language: a key and its named arguments. An argument
 * may itself be a {@code Msg} or a {@link MessageKey}; it is rendered in the same language.
 */
public record Msg(MessageKey key, Map<String, Object> args) {
    public Msg {
        Objects.requireNonNull(key, "a message needs a key");
        args = Map.copyOf(args);
    }

    public static Msg of(MessageKey key, Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("arguments come in name/value pairs: " + namesAndValues.length + " values given");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (!(namesAndValues[i] instanceof String name)) {
                throw new IllegalArgumentException("argument " + i / 2 + " has no name: " + namesAndValues[i]);
            }
            Object value = Objects.requireNonNull(namesAndValues[i + 1], "argument " + name + " is null");
            if (args.put(name, value) != null) throw new IllegalArgumentException("argument " + name + " given twice");
        }
        return new Msg(key, args);
    }
}
