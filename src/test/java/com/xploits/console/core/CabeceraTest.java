package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CabeceraTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    private static final List<Instantanea.EstadoModulo> MODULOS = List.of(
        new Instantanea.EstadoModulo("auto-pvp", true, "SUPERFICIE · Foo"),
        new Instantanea.EstadoModulo("auto-travel", false, "armado"));

    @Test
    void lasCuatroFilasCalculadasAMano() {
        Instantanea i = new Instantanea("minecraft:the_nether", 3, 1, 64, 87, new Instantanea.Progreso(3, 12, 850),
            null, 18.5, 20, 128, 7, 0, null, MODULOS, Language.ES);
        assertEquals(List.of(
            "dimensión the_nether · jugadores cargados 3 · nuestros 1",
            "cohetes 64 · elytra 87 % · viaje waypoint 3/12 · faltan 850 bloques",
            "vida 18.5 · armadura 20 · en barra: obsidiana 128 · cristales 7 · telarañas 0 · yunques ?",
            "● auto-pvp SUPERFICIE · Foo  ○ auto-travel"), Cabecera.filas(i, Glifos.UNICODE, ES));
    }

    @Test
    void unBarridoYSinElytra() {
        Instantanea barrido = new Instantanea("minecraft:the_nether", 0, 0, 64, 87, null,
            new Instantanea.Progreso(2, 7, 12400), 20.0, 0, 0, 0, 0, 0, List.of(), Language.ES);
        assertEquals("cohetes 64 · elytra 87 % · barrido pasada 2/7 · faltan 12400 bloques",
            Cabecera.filas(barrido, Glifos.UNICODE, ES).get(1));
        Instantanea sinElytra = new Instantanea("minecraft:overworld", 0, 0, 64, -1, null, null, 20.0, 0, 0, 0, 0, 0, List.of(), Language.ES);
        assertEquals("cohetes 64 · elytra no lleva · sin viaje", Cabecera.filas(sinElytra, Glifos.UNICODE, ES).get(1));
    }

    @Test
    void sinJugadorTodoEsInterrogacion() {
        assertEquals(List.of(
            "dimensión ? · jugadores cargados ? · nuestros ?",
            "cohetes ? · elytra ? · sin viaje",
            "vida ? · armadura ? · en barra: obsidiana ? · cristales ? · telarañas ? · yunques ?",
            "sin módulos"), Cabecera.filas(Instantanea.sinJugador(List.of(), Language.ES), Glifos.UNICODE, ES));
    }

    @Test
    void enAscii() {
        Instantanea i = Instantanea.sinJugador(MODULOS, Language.ES);
        assertEquals("* auto-pvp SUPERFICIE · Foo  o auto-travel", Cabecera.filas(i, Glifos.ASCII, ES).get(3));
    }

    @Test
    void inEnglish() {
        Instantanea i = new Instantanea("minecraft:the_nether", 3, 1, 64, -1, null, null, 18.5, 20, 128, 7, 0, null,
            List.of(), Language.EN);
        assertEquals(List.of(
            "dimension the_nether · players loaded 3 · ours 1",
            "rockets 64 · elytra not worn · no travel",
            "health 18.5 · armor 20 · in hotbar: obsidian 128 · crystals 7 · webs 0 · anvils ?",
            "no modules"), Cabecera.filas(i, Glifos.UNICODE, EN));
    }
}
