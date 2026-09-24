package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotacionTest {
    private static final LocalDate HOY = LocalDate.of(2026, 9, 23);
    private static final long MIB = 1L << 20;

    @Test
    void elLimiteDelVivoEsUnMebibyte() {
        assertEquals(1_048_576L, Rotacion.LIMITE_VIVO);
        assertFalse(Rotacion.rotarVivo(1_048_000, 576));
        assertTrue(Rotacion.rotarVivo(1_048_000, 577));
    }

    @Test
    void seGuardanTreintaDias() {
        List<Rotacion.Fichero> ficheros = List.of(
            new Rotacion.Fichero("2026-08-25.log", 1, LocalDate.of(2026, 8, 25)),
            new Rotacion.Fichero("2026-08-24.log", 1, LocalDate.of(2026, 8, 24)),
            new Rotacion.Fichero("2026-09-23.log", 1, HOY));
        assertEquals(List.of("2026-08-24.log"), Rotacion.borrar(ficheros, HOY));
    }

    @Test
    void siPasaDeSesentaYCuatroMebibytesSeBorraElMasViejo() {
        List<Rotacion.Fichero> ficheros = List.of(
            new Rotacion.Fichero("2026-09-22.log", 30 * MIB, LocalDate.of(2026, 9, 22)),
            new Rotacion.Fichero("2026-09-21.log", 30 * MIB, LocalDate.of(2026, 9, 21)),
            new Rotacion.Fichero("2026-09-23.log", 30 * MIB, HOY));
        assertEquals(List.of("2026-09-21.log"), Rotacion.borrar(ficheros, HOY));
    }

    @Test
    void elDeHoyNuncaSeBorra() {
        List<Rotacion.Fichero> ficheros = List.of(new Rotacion.Fichero("2026-09-23.log", 100 * MIB, HOY));
        assertEquals(List.of(), Rotacion.borrar(ficheros, HOY));
    }

    @Test
    void laFechaSaleDelNombreYLoDemasSeIgnora() {
        assertEquals(Optional.of(HOY), Rotacion.fechaDe("2026-09-23.log"));
        assertEquals(Optional.empty(), Rotacion.fechaDe("notas.txt"));
        assertEquals(Optional.empty(), Rotacion.fechaDe("2026-13-40.log"));
        assertEquals("2026-09-23.log", Rotacion.nombreDelDia(HOY));
    }
}
