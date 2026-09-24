package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeFormatTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void aGoodFormatComesOutFormatted() {
        assertEquals(new SafeFormat.Result("a b", false), SafeFormat.apply(ES, "a %s", "b"));
        assertEquals(new SafeFormat.Result("50%", false), SafeFormat.apply(ES, "50%%"));
    }

    @Test
    void aBrokenFormatKeepsTheMessageAndSaysSo() {
        SafeFormat.Result r = SafeFormat.apply(ES, "50%");
        assertTrue(r.broken());
        assertTrue(r.text().startsWith("50% [formato roto: "), r.text());
        assertEquals(new SafeFormat.Result("%d [formato roto: IllegalFormatConversionException]", true),
            SafeFormat.apply(ES, "%d", "x"));
        assertEquals(new SafeFormat.Result("%s [formato roto: MissingFormatArgumentException]", true),
            SafeFormat.apply(ES, "%s"));
    }

    @Test
    void theBrokenMarkInEnglish() {
        assertEquals(new SafeFormat.Result("%s [broken format: MissingFormatArgumentException]", true),
            SafeFormat.apply(EN, "%s"));
    }
}
