package com.xploits.shared.core.i18n;

import java.util.Locale;
import java.util.Optional;

/**
 * What the player picked. The display names are each language's own name and never change with the
 * language: Meteor saves an enum setting by {@code toString()}, so a translated name would lose the
 * saved choice.
 */
public enum LanguageChoice {
    AUTO("auto", "Auto"),
    SPANISH("es", "Español"), // i18n: allowed
    ENGLISH("en", "English");

    private final String code;
    private final String display;

    LanguageChoice(String code, String display) {
        this.code = code;
        this.display = display;
    }

    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return display;
    }

    public static Optional<LanguageChoice> fromCode(String code) {
        if (code == null) return Optional.empty();
        String c = code.strip().toLowerCase(Locale.ROOT);
        for (LanguageChoice l : values()) {
            if (l.code.equals(c)) return Optional.of(l);
        }
        return Optional.empty();
    }

    /** Auto: any Minecraft {@code es_*} language is Spanish, everything else English. */
    public Language resolve(String minecraftLanguage) {
        return switch (this) {
            case SPANISH -> Language.ES;
            case ENGLISH -> Language.EN;
            case AUTO -> minecraftLanguage != null && minecraftLanguage.toLowerCase(Locale.ROOT).startsWith("es_")
                ? Language.ES : Language.EN;
        };
    }
}
