package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which of the settings restock changes has a value in {@code baritone/settings.txt} that Baritone would read otherwise
 * than the player meant (restock spec §3 "Baritone", ruling of the printer's Task 10 review): asked to the committed
 * {@link BaritoneSession#playerValues} one setting at a time, in the order restock sets them, so the refusal can name it.
 */
public final class BaritoneValues {
    private BaritoneValues() {
    }

    /** @param file {@link BaritoneSession#parse}'s map: lower-cased names to raw values */
    public static Optional<String> unreadable(Map<String, String> file) {
        for (String name : BaritoneSession.CHANGED) {
            String key = name.toLowerCase(Locale.ROOT);
            String raw = file.get(key);
            if (raw == null) continue;
            if (BaritoneSession.playerValues(Map.of(key, raw)).isEmpty()) return Optional.of(name);
        }
        return Optional.empty();
    }
}
