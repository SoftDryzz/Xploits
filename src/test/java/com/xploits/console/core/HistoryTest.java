package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistorialTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });
    @Test
    void unMensajeConFechaDesfaseNivelYFuente() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  INFO   auto-pvp  hola",
            Historial.linea(new Registro.Mensaje(0, 0, "s", Nivel.INFO, "auto-pvp", "hola"), ZoneOffset.UTC, ES));
        assertEquals("1970-01-01 00:00:01.500 +00:00  AVISO  auto-travel  cuidado",
            Historial.linea(new Registro.Mensaje(0, 1500, "s", Nivel.AVISO, "auto-travel", "cuidado"), ZoneOffset.UTC, ES));
    }

    @Test
    void lasLineasDeContinuacionVanSangradas() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  ERROR  xploits  uno\n    dos",
            Historial.linea(new Registro.Mensaje(0, 0, "s", Nivel.ERROR, "xploits", "uno\ndos"), ZoneOffset.UTC, ES));
    }

    @Test
    void unEscNoLlegaAlFichero() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  INFO   f  a?b",
            Historial.linea(new Registro.Mensaje(0, 0, "s", Nivel.INFO, "f", "a\u001bb"), ZoneOffset.UTC, ES));
    }

    @Test
    void juegoYPerdidasTambienFotosYFinesNo() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  JUEGO  inicio",
            Historial.linea(new Registro.Juego(0, 0, "s", "inicio"), ZoneOffset.UTC, ES));
        assertEquals("1970-01-01 00:00:00.000 +00:00  AVISO  consola  se perdieron 4 mensajes: la cola de la consola se llenó",
            Historial.linea(new Registro.Perdida(0, 0, "s", 4), ZoneOffset.UTC, ES));
        assertNull(Historial.linea(new Registro.Foto(0, 0, "s", Instantanea.sinJugador(List.of(), Language.ES)), ZoneOffset.UTC, ES));
        assertNull(Historial.linea(new Registro.Fin(0, 0, "s", "l", "m"), ZoneOffset.UTC, ES));
    }

    @Test
    void inEnglishTheLevelsAndTheGameLineToo() {
        assertEquals("1970-01-01 00:00:00.000 +00:00  WARN   auto-travel  careful",
            Historial.linea(new Registro.Mensaje(0, 0, "s", Nivel.AVISO, "auto-travel", "careful"), ZoneOffset.UTC, EN));
        assertEquals("1970-01-01 00:00:00.000 +00:00  GAME   start",
            Historial.linea(new Registro.Juego(0, 0, "s", "inicio"), ZoneOffset.UTC, EN));
    }
}
