package com.xploits.autotpy.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The auto-tpy texts moved to the catalogs verbatim, and read in English too. */
class TpyTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    @Test
    void theAcceptNoticeReadsExactlyAsBefore() {
        assertEquals("TPA aceptada de Steve.", ES.render(Msg.of(TpyText.ACCEPTED, "name", "Steve")));
    }

    @Test
    void theIgnoredNoticeReadsExactlyAsBefore() {
        assertEquals("TPA ignorada de Steve: no está en la lista.", ES.render(Msg.of(TpyText.IGNORED, "name", "Steve")));
    }

    @Test
    void theIgnoredNoticeReadsInEnglish() {
        assertEquals("Ignored TPA from Steve: not on the list.", EN.render(Msg.of(TpyText.IGNORED, "name", "Steve")));
    }
}
