package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeaderTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final List<GameSnapshot.ModuleStatus> MODULES = List.of(
        new GameSnapshot.ModuleStatus("auto-pvp", true, "SUPERFICIE · Foo"),
        new GameSnapshot.ModuleStatus("auto-travel", false, "armado"));

    @Test
    void theFourRowsWorkedOutByHand() {
        GameSnapshot s = new GameSnapshot("minecraft:the_nether", 3, 1, 64, 87, new GameSnapshot.Progress(3, 12, 850),
            null, 18.5, 20, 128, 7, 0, null, MODULES, Language.ES);
        assertEquals(List.of(
            "dimensión the_nether · jugadores cargados 3 · nuestros 1",
            "cohetes 64 · elytra 87 % · viaje waypoint 3/12 · faltan 850 bloques",
            "vida 18,5 · armadura 20 · en barra: obsidiana 128 · cristales 7 · telarañas 0 · yunques ?",
            "● auto-pvp SUPERFICIE · Foo  ○ auto-travel"), Header.rows(s, Glyphs.UNICODE, ES));
    }

    @Test
    void aSweepAndNoElytra() {
        GameSnapshot sweep = new GameSnapshot("minecraft:the_nether", 0, 0, 64, 87, null,
            new GameSnapshot.Progress(2, 7, 12400), 20.0, 0, 0, 0, 0, 0, List.of(), Language.ES);
        assertEquals("cohetes 64 · elytra 87 % · barrido pasada 2/7 · faltan 12400 bloques",
            Header.rows(sweep, Glyphs.UNICODE, ES).get(1));
        GameSnapshot noElytra = new GameSnapshot("minecraft:overworld", 0, 0, 64, -1, null, null, 20.0, 0, 0, 0, 0, 0, List.of(), Language.ES);
        assertEquals("cohetes 64 · elytra no lleva · sin viaje", Header.rows(noElytra, Glyphs.UNICODE, ES).get(1));
    }

    @Test
    void withoutAPlayerEverythingIsAQuestionMark() {
        assertEquals(List.of(
            "dimensión ? · jugadores cargados ? · nuestros ?",
            "cohetes ? · elytra ? · sin viaje",
            "vida ? · armadura ? · en barra: obsidiana ? · cristales ? · telarañas ? · yunques ?",
            "sin módulos"), Header.rows(GameSnapshot.withoutPlayer(List.of(), Language.ES), Glyphs.UNICODE, ES));
    }

    @Test
    void inAscii() {
        GameSnapshot s = GameSnapshot.withoutPlayer(MODULES, Language.ES);
        assertEquals("* auto-pvp SUPERFICIE · Foo  o auto-travel", Header.rows(s, Glyphs.ASCII, ES).get(3));
    }

    @Test
    void inEnglish() {
        GameSnapshot s = new GameSnapshot("minecraft:the_nether", 3, 1, 64, -1, null, null, 18.5, 20, 128, 7, 0, null,
            List.of(), Language.EN);
        assertEquals(List.of(
            "dimension the_nether · players loaded 3 · ours 1",
            "rockets 64 · elytra not worn · no travel",
            "health 18.5 · armor 20 · in hotbar: obsidian 128 · crystals 7 · webs 0 · anvils ?",
            "no modules"), Header.rows(s, Glyphs.UNICODE, EN));
    }
}
