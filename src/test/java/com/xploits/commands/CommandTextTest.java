package com.xploits.commands;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The .xploits texts moved to the catalogs verbatim, and read in English too. */
class CommandTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private static Msg hit(Object ago) {
        return Msg.of(CommandText.FIND_HIT, "item", "obsidian", "count", 64, "place", "nether",
            "shulker", Msg.of(CommandText.FIND_IN_SHULKER, "name", "Bloques"), "ago", ago);
    }

    @Test
    void theNotSeenWarningReadsExactlyAsBefore() {
        assertEquals("No he visto \"obsi\" en ningún contenedor. Recuerda que un cofre solo entra en el índice cuando lo abres, "
            + "y que los shulkers que llevas encima tampoco están indexados.",
            ES.render(Msg.of(CommandText.FIND_NOT_SEEN, "query", "obsi")));
    }

    @Test
    void aHitReadsExactlyAsBefore() {
        assertEquals("  obsidian x64 · nether · en shulker \"Bloques\" · visto hace 3 días",
            ES.render(hit(Msg.of(CommandText.AGO_DAYS, "n", 3L))));
        assertEquals("1 sitio con \"obsi\":", ES.render(Msg.of(CommandText.FIND_HEADER_ONE, "count", 1, "query", "obsi")));
        assertEquals("El módulo auto-pvp no está registrado.",
            ES.render(Msg.of(CommandText.MODULE_NOT_REGISTERED, "module", "auto-pvp")));
    }

    @Test
    void aHitReadsInEnglish() {
        assertEquals("  obsidian x64 · nether · in shulker \"Bloques\" · seen 5 min ago",
            EN.render(hit(Msg.of(CommandText.AGO_MINUTES, "n", 5L))));
        assertEquals("12 places with \"obsi\":", EN.render(Msg.of(CommandText.FIND_HEADER_MANY, "count", 12, "query", "obsi")));
    }
}
