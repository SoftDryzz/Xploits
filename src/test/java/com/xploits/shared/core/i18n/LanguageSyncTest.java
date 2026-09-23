package com.xploits.shared.core.i18n;

import org.junit.jupiter.api.Test;

import static com.xploits.shared.core.i18n.LanguageSync.Change.CHANGED;
import static com.xploits.shared.core.i18n.LanguageSync.Change.IGNORED;
import static com.xploits.shared.core.i18n.LanguageSync.Change.UNCHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageSyncTest {
    @Test
    void meteorLoadingTheSavedSettingDoesNotOverrideTheFile() {
        LanguageSync s = new LanguageSync(LanguageChoice.SPANISH);
        assertEquals(IGNORED, s.settingChanged(LanguageChoice.ENGLISH));
        assertEquals(LanguageChoice.SPANISH, s.current());
        assertEquals(LanguageChoice.SPANISH, s.fileChoice());
    }

    @Test
    void forcingTheSettingOnTheFirstTickIsStillIgnored() {
        LanguageSync s = new LanguageSync(LanguageChoice.SPANISH);
        assertEquals(IGNORED, s.settingChanged(LanguageChoice.SPANISH));
        s.finishLoading();
        assertFalse(s.loading());
    }

    @Test
    void afterLoadingThePlayerChangesIt() {
        LanguageSync s = new LanguageSync(LanguageChoice.AUTO);
        s.finishLoading();
        assertEquals(CHANGED, s.settingChanged(LanguageChoice.ENGLISH));
        assertEquals(LanguageChoice.ENGLISH, s.current());
        assertEquals(UNCHANGED, s.settingChanged(LanguageChoice.ENGLISH));
    }

    @Test
    void theCommandWorksEvenWhileLoading() {
        LanguageSync s = new LanguageSync(LanguageChoice.AUTO);
        assertTrue(s.loading());
        assertEquals(CHANGED, s.choose(LanguageChoice.SPANISH));
        assertEquals(UNCHANGED, s.choose(LanguageChoice.SPANISH));
        assertEquals(LanguageChoice.SPANISH, s.fileChoice());
    }
}
