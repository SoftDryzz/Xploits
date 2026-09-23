package com.xploits.shared.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageFileTest {
    @Test
    void missingFileIsAuto() {
        assertEquals(new LanguageFile.Read(LanguageChoice.AUTO, null), LanguageFile.parse(Optional.empty()));
    }

    @Test
    void readsEachCode() {
        assertEquals(LanguageChoice.SPANISH, LanguageFile.parse(Optional.of("es\n")).choice());
        assertEquals(LanguageChoice.ENGLISH, LanguageFile.parse(Optional.of("en")).choice());
        assertEquals(LanguageChoice.AUTO, LanguageFile.parse(Optional.of("auto\n")).choice());
    }

    @Test
    void toleratesBomCrlfCaseAndSpaces() {
        LanguageFile.Read r = LanguageFile.parse(Optional.of("﻿ ES \r\n"));
        assertEquals(LanguageChoice.SPANISH, r.choice());
        assertNull(r.unreadable());
    }

    @Test
    void unknownContentIsAutoAndSaysWhat() {
        LanguageFile.Read r = LanguageFile.parse(Optional.of("français\n"));
        assertEquals(LanguageChoice.AUTO, r.choice());
        assertEquals("français", r.unreadable());
    }

    @Test
    void unreadableContentIsCutShort() {
        LanguageFile.Read r = LanguageFile.parse(Optional.of("x".repeat(500)));
        assertEquals(40, r.unreadable().length());
    }

    @Test
    void writesOneWord() {
        assertEquals("es\n", LanguageFile.write(LanguageChoice.SPANISH));
        assertEquals("auto\n", LanguageFile.write(LanguageChoice.AUTO));
    }
}
