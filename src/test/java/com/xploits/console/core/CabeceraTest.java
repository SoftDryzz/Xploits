package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CabeceraTest {
    private static final List<Instantanea.EstadoModulo> MODULOS = List.of(
        new Instantanea.EstadoModulo("auto-pvp", true, "SUPERFICIE · Foo"),
        new Instantanea.EstadoModulo("auto-travel", false, "armado"));

    @Test
    void lasCuatroFilasCalculadasAMano() {
        Instantanea i = new Instantanea("minecraft:the_nether", 3, 1, 64, 87, new Instantanea.Progreso(3, 12, 850),
            null, 18.5, 20, 128, 7, 0, null, MODULOS);
        assertEquals(List.of(
            "dimensión the_nether · jugadores cargados 3 · nuestros 1",
            "cohetes 64 · elytra 87 % · viaje waypoint 3/12 · faltan 850 bloques",
            "vida 18.5 · armadura 20 · en barra: obsidiana 128 · cristales 7 · telarañas 0 · yunques ?",
            "● auto-pvp SUPERFICIE · Foo  ○ auto-travel"), Cabecera.filas(i, Glifos.UNICODE));
    }

    @Test
    void unBarridoYSinElytra() {
        Instantanea barrido = new Instantanea("minecraft:the_nether", 0, 0, 64, 87, null,
            new Instantanea.Progreso(2, 7, 12400), 20.0, 0, 0, 0, 0, 0, List.of());
        assertEquals("cohetes 64 · elytra 87 % · barrido pasada 2/7 · faltan 12400 bloques",
            Cabecera.filas(barrido, Glifos.UNICODE).get(1));
        Instantanea sinElytra = new Instantanea("minecraft:overworld", 0, 0, 64, -1, null, null, 20.0, 0, 0, 0, 0, 0, List.of());
        assertEquals("cohetes 64 · elytra no lleva · sin viaje", Cabecera.filas(sinElytra, Glifos.UNICODE).get(1));
    }

    @Test
    void sinJugadorTodoEsInterrogacion() {
        assertEquals(List.of(
            "dimensión ? · jugadores cargados ? · nuestros ?",
            "cohetes ? · elytra ? · sin viaje",
            "vida ? · armadura ? · en barra: obsidiana ? · cristales ? · telarañas ? · yunques ?",
            "sin módulos"), Cabecera.filas(Instantanea.sinJugador(List.of()), Glifos.UNICODE));
    }

    @Test
    void enAscii() {
        Instantanea i = Instantanea.sinJugador(MODULOS);
        assertEquals("* auto-pvp SUPERFICIE · Foo  o auto-travel", Cabecera.filas(i, Glifos.ASCII).get(3));
    }
}
