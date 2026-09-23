package com.xploits.elytra.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The elytra-replace texts moved to the catalogs verbatim, and read in English too. */
class ElytraTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    @Test
    void theSwapNoticeReadsExactlyAsBefore() {
        assertEquals("Elytra cambiada: la puesta estaba al 8 %.", ES.render(Msg.of(ElytraText.SWAPPED, "percent", 8)));
    }

    @Test
    void theNoBetterSpareWarningReadsExactlyAsBefore() {
        assertEquals("Elytra al 9 % y ningún repuesto mejor que ella, aunque alguno sí llegue al 50 % mínimo.",
            ES.render(Msg.of(ElytraText.NO_SPARE_BETTER, "worn", 9, "minimum", 50)));
    }

    @Test
    void theNoSpareWarningReadsInEnglish() {
        assertEquals("Elytra at 9% and no spare above 50%.",
            EN.render(Msg.of(ElytraText.NO_SPARE_ABOVE_MINIMUM, "worn", 9, "minimum", 50)));
    }
}
