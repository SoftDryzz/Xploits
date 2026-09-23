package com.xploits.console.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Cuánto crece lo que la consola deja en disco (spec consola §5): {@code vivo.log} rota a 1 MiB; el
 * historial guarda 30 días o 64 MiB, lo que llegue antes, y el fichero de hoy no se borra nunca.
 */
public final class Rotacion {
    public static final long LIMITE_VIVO = 1L << 20;
    public static final int DIAS = 30;
    public static final long LIMITE_HISTORIAL = 64L << 20;

    private Rotacion() {
    }

    public static boolean rotarVivo(long tamanoActual, long bytesNuevos) {
        return tamanoActual + bytesNuevos > LIMITE_VIVO;
    }

    public record Fichero(String nombre, long tamano, LocalDate fecha) {
    }

    /** La fecha de un fichero del historial, o vacío si el nombre no es {@code AAAA-MM-DD.log}. */
    public static Optional<LocalDate> fechaDe(String nombre) {
        if (!nombre.endsWith(".log")) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(nombre.substring(0, nombre.length() - ".log".length())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String nombreDelDia(LocalDate dia) {
        return dia + ".log";
    }

    /** Qué ficheros del historial borrar, del más viejo al más nuevo. */
    public static List<String> borrar(List<Fichero> ficheros, LocalDate hoy) {
        LocalDate primeroQueSeGuarda = hoy.minusDays(DIAS - 1);
        List<String> borrar = new ArrayList<>();
        List<Fichero> quedan = new ArrayList<>();
        for (Fichero f : ficheros.stream().sorted(Comparator.comparing(Fichero::fecha)).toList()) {
            if (f.fecha().isBefore(primeroQueSeGuarda)) borrar.add(f.nombre());
            else quedan.add(f);
        }
        long total = quedan.stream().mapToLong(Fichero::tamano).sum();
        for (Fichero f : quedan) {
            if (total <= LIMITE_HISTORIAL || !f.fecha().isBefore(hoy)) break;
            borrar.add(f.nombre());
            total -= f.tamano();
        }
        return borrar;
    }
}
