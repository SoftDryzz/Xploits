package com.xploits.shared.core.i18n;

import java.util.Optional;

/** The content of {@code meteor-client/xploits/language.txt}: one word, {@code auto}, {@code es} or {@code en}. */
public final class LanguageFile {
    private static final int SHOWN = 40;

    private LanguageFile() {
    }

    /** {@code unreadable} is what the file held when it was not a known word, cut to 40 chars; null otherwise. */
    public record Read(LanguageChoice choice, String unreadable) {
    }

    public static Read parse(Optional<String> content) {
        if (content.isEmpty()) return new Read(LanguageChoice.AUTO, null);
        String word = content.get().replace("﻿", "").strip();
        return LanguageChoice.fromCode(word)
            .map(c -> new Read(c, null))
            .orElseGet(() -> new Read(LanguageChoice.AUTO, word.length() > SHOWN ? word.substring(0, SHOWN) : word));
    }

    public static String write(LanguageChoice choice) {
        return choice.code() + "\n";
    }
}
