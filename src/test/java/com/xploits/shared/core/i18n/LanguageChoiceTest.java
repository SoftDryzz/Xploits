package com.xploits.shared.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageChoiceTest {
    @Test
    void fixedChoicesIgnoreMinecraft() {
        assertEquals(Language.ES, LanguageChoice.SPANISH.resolve("en_us"));
        assertEquals(Language.EN, LanguageChoice.ENGLISH.resolve("es_es"));
    }

    @Test
    void autoFollowsAnySpanishVariant() {
        assertEquals(Language.ES, LanguageChoice.AUTO.resolve("es_es"));
        assertEquals(Language.ES, LanguageChoice.AUTO.resolve("es_mx"));
        assertEquals(Language.ES, LanguageChoice.AUTO.resolve("ES_MX"));
    }

    @Test
    void autoFallsBackToEnglish() {
        assertEquals(Language.EN, LanguageChoice.AUTO.resolve("en_us"));
        assertEquals(Language.EN, LanguageChoice.AUTO.resolve("ca_es"));
        assertEquals(Language.EN, LanguageChoice.AUTO.resolve("eu_es"));
        assertEquals(Language.EN, LanguageChoice.AUTO.resolve("es"));
        assertEquals(Language.EN, LanguageChoice.AUTO.resolve(""));
        assertEquals(Language.EN, LanguageChoice.AUTO.resolve(null));
    }

    @Test
    void displayNamesAreTheLanguagesOwnAndNeverChange() {
        assertEquals("Auto", LanguageChoice.AUTO.toString());
        assertEquals("Español", LanguageChoice.SPANISH.toString());
        assertEquals("English", LanguageChoice.ENGLISH.toString());
    }

    @Test
    void codes() {
        assertEquals(Optional.of(LanguageChoice.SPANISH), LanguageChoice.fromCode("es"));
        assertEquals(Optional.of(LanguageChoice.AUTO), LanguageChoice.fromCode(" AUTO "));
        assertEquals(Optional.empty(), LanguageChoice.fromCode("fr"));
        assertEquals(Optional.empty(), LanguageChoice.fromCode(null));
        assertEquals(Optional.of(Language.EN), Language.fromCode("en"));
        assertEquals(Optional.empty(), Language.fromCode("EN_US"));
    }
}
