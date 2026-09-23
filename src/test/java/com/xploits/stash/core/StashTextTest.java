package com.xploits.stash.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The stash-keeper texts moved to the catalogs verbatim, and read in English too. */
class StashTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    @Test
    void theStatusReadsExactlyAsBefore() {
        assertEquals("12 contenedores indexados, 3 shulkers dentro. Recuerda: los cofres solo entran al abrirlos;"
            + " los shulkers, con verlos.", ES.render(Msg.of(StashText.STATUS, "containers", 12, "shulkers", 3)));
    }

    @Test
    void theIndexedLogLineReadsExactlyAsBeforeAndWithoutPosition() {
        ContainerKey chest = ContainerKey.block("minecraft:overworld", 300, 64, 400);
        assertEquals("Indexado un contenedor (overworld a 500 bloques, 4 tipos, 1 shulkers).",
            ES.render(Msg.of(StashText.INDEXED_LOG, "where", chest.sinPosicion("minecraft:overworld", 0.0, 0.0),
                "types", 4, "shulkers", 1)));
    }

    @Test
    void theDistanceReadsInEnglish() {
        ContainerKey chest = ContainerKey.block("minecraft:overworld", 300, 64, 400);
        assertEquals("overworld, 500 blocks away", EN.render(chest.sinPosicion("minecraft:overworld", 0.0, 0.0)));
    }
}
