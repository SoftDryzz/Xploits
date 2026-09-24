package com.xploits.pvp.recorder.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The recorder's labels exist in both catalogs and read as meant. */
class RecorderTextTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    @Test
    void theKeysLiveInTheRecorderArea() {
        assertEquals("recorder.kind-respawn-point", RecorderText.KIND_RESPAWN_POINT.id());
        assertEquals("recorder.mode-auto-pvp", RecorderText.MODE_AUTO_PVP.id());
    }

    @Test
    void everyOutcomeAndModeIsNamedInBothCatalogs() {
        for (FightOutcome outcome : FightOutcome.values()) {
            assertFalse(ES.render(Msg.of(outcome.label())).isBlank(), outcome.name());
            assertFalse(EN.render(Msg.of(outcome.label())).isBlank(), outcome.name());
        }
        for (FightMode mode : FightMode.values()) {
            assertFalse(ES.render(Msg.of(mode.label())).isBlank(), mode.name());
            assertFalse(EN.render(Msg.of(mode.label())).isBlank(), mode.name());
        }
        assertEquals("lost", EN.render(Msg.of(FightOutcome.LOST.label())));
        assertEquals("perdida", ES.render(Msg.of(FightOutcome.LOST.label())));
    }

    @Test
    void theActivityCountsSeconds() {
        assertEquals("fighting · 43 s", EN.render(Msg.of(RecorderText.ACTIVITY, "seconds", 43)));
        assertEquals("peleando · 43 s", ES.render(Msg.of(RecorderText.ACTIVITY, "seconds", 43)));
    }

    @Test
    void namesJoinWithACommaAndNothingIsEmpty() {
        Msg two = Msg.of(RecorderText.JOIN_COMMA, "first", "Foo", "rest", "Bar");
        assertEquals("Foo, Bar", EN.render(two));
        assertEquals("", EN.render(Msg.of(RecorderText.NOTHING)));
        assertEquals("Foo", EN.render(Msg.of(RecorderText.CONCAT, "first", "Foo", "second", Msg.of(RecorderText.NOTHING))));
    }
}
