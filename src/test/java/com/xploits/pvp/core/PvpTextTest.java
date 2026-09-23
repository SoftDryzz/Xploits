package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The auto-pvp texts moved to the catalogs verbatim, and read in English too. */
class PvpTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private static ActionWatch.Idle obsidianTrio() {
        return new ActionWatch.Idle(Resource.OBSIDIAN,
            List.of(ManagedModules.AUTO_TRAP, ManagedModules.SURROUND, ManagedModules.HOLE_FILLER), 60);
    }

    @Test
    void theJointIdleWarningReadsExactlyAsBefore() {
        assertEquals("auto-trap, surround y hole-filler llevan 3 s encendidos, con enemigo delante y obsidiana de"
            + " sobra, y la pila no ha bajado. No puedo decirte cuál de los 3 falla, y con esta medida no se puede:"
            + " el inventario dice cuánta obsidiana queda, no quién la colocó, así que lo único que esto afirma es"
            + " que no ha colocado ninguno. No apago ninguno. Sospechosos — auto-trap: whitelist (de fábrica trae"
            + " obsidiana y obsidiana llorosa, y el bloque de netherita NO está en ella), place-range y walls-range"
            + " (4 de fábrica, y manda el segundo en cuanto haya algo por medio) y top-blocks/bottom-blocks;"
            + " surround: blocks (de fábrica trae obsidiana, obsidiana llorosa y bloque de netherita, y yo solo te"
            + " cuento la obsidiana) y only-on-ground; hole-filler: only-moving (encendido de fábrica, y en las"
            + " fuentes descarta al que SE MUEVE, no al que está quieto), feet-range (1,5 de fábrica desde los pies"
            + " del objetivo, ya predichos) e ignore-safe.",
            ES.render(ActionWatch.reason(obsidianTrio())));
    }

    @Test
    void theSharedShortageSkipReadsExactlyAsBefore() {
        Msg skip = Msg.of(PvpText.NOT_ENABLING, "module", "hole-filler", "reason",
            Msg.of(PvpText.SHORTAGE_SHARED, "have", 9, "others",
                Msg.of(PvpText.JOIN_AND, "first", "auto-trap", "second", "surround"), "left", 0, "minimum", 1));
        assertEquals("No enciendo hole-filler: tienes 9 y auto-trap y surround ya han apartado: quedan 0 y necesita 1.",
            ES.render(skip));
    }

    @Test
    void theThreatNoticeReadsInEnglishWithADot() {
        assertEquals("AMENAZADO · 12.5 damage is already aimed at you and you have 7.0 health left.",
            EN.render(Msg.of(PvpText.THREATENED, "damage", 12.5, "health", 7.0)));
        assertEquals("AMENAZADO · 12,5 de daño ya te apunta y te quedan 7,0 de vida.",
            ES.render(Msg.of(PvpText.THREATENED, "damage", 12.5, "health", 7.0)));
    }
}
