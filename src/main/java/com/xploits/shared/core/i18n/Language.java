package com.xploits.shared.core.i18n;

import java.util.Optional;

/** A language text is rendered in. {@link LanguageChoice} is what the player picked. */
public enum Language {
    ES("es"),
    EN("en");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Language> fromCode(String code) {
        for (Language l : values()) {
            if (l.code.equals(code)) return Optional.of(l);
        }
        return Optional.empty();
    }
}
