package com.xploits.console.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Las líneas de {@code vivo.log} (spec consola §5): una por registro, campos separados por
 * tabulador y cada campo escapado. {@code seq} crece de uno en uno dentro de una sesión del juego:
 * un salto es un registro perdido.
 */
public sealed interface Registro permits Registro.Mensaje, Registro.Foto, Registro.Juego, Registro.Fin, Registro.Perdida {
    int VERSION = 1;
    String PREFIJO_CABECERA = "#xploits-consola";

    long seq();

    long epochMs();

    String sesion();

    /** Un mensaje de un módulo o del comando. */
    record Mensaje(long seq, long epochMs, String sesion, Nivel nivel, String fuente, String texto) implements Registro {
    }

    /** Una foto de la cabecera. Hace también de latido del juego. */
    record Foto(long seq, long epochMs, String sesion, Instantanea instantanea) implements Registro {
    }

    /** El juego empieza a escribir ({@code inicio}) o se apaga ({@code fin}). */
    record Juego(long seq, long epochMs, String sesion, String motivo) implements Registro {
    }

    /** La orden de cerrarse, solo para la ventana de ese lanzamiento. */
    record Fin(long seq, long epochMs, String sesion, String lanzamiento, String motivo) implements Registro {
    }

    /** Se perdieron mensajes porque la cola estaba llena. */
    record Perdida(long seq, long epochMs, String sesion, long cuantos) implements Registro {
    }

    default String codificar() {
        List<String> campos = switch (this) {
            case Mensaje m -> List.of("R", Long.toString(m.seq()), Long.toString(m.epochMs()), m.sesion(),
                String.valueOf(m.nivel().codigo()), m.fuente(), m.texto());
            case Foto f -> List.of("S", Long.toString(f.seq()), Long.toString(f.epochMs()), f.sesion(),
                f.instantanea().codificar());
            case Juego j -> List.of("J", Long.toString(j.seq()), Long.toString(j.epochMs()), j.sesion(), j.motivo());
            case Fin f -> List.of("F", Long.toString(f.seq()), Long.toString(f.epochMs()), f.sesion(),
                f.lanzamiento(), f.motivo());
            case Perdida p -> List.of("P", Long.toString(p.seq()), Long.toString(p.epochMs()), p.sesion(),
                Long.toString(p.cuantos()));
        };
        return campos.stream().map(Escape::escapar).collect(Collectors.joining("\t"));
    }

    static Registro decodificar(String linea) {
        List<String> c = Escape.partir(linea, '\t').stream().map(Escape::desescapar).toList();
        String tipo = c.get(0);
        int esperados = switch (tipo) {
            case "R" -> 7;
            case "F" -> 6;
            case "S", "J", "P" -> 5;
            default -> throw new IllegalArgumentException("tipo de registro desconocido: " + tipo);
        };
        if (c.size() != esperados) {
            throw new IllegalArgumentException("un registro " + tipo + " lleva " + esperados + " campos y este lleva " + c.size());
        }
        long seq = numero(c.get(1), "seq");
        long epochMs = numero(c.get(2), "epochMs");
        String sesion = c.get(3);
        return switch (tipo) {
            case "R" -> {
                if (c.get(4).length() != 1) throw new IllegalArgumentException("nivel mal formado: " + c.get(4));
                yield new Mensaje(seq, epochMs, sesion, Nivel.de(c.get(4).charAt(0)), c.get(5), c.get(6));
            }
            case "S" -> new Foto(seq, epochMs, sesion, Instantanea.decodificar(c.get(4)));
            case "J" -> new Juego(seq, epochMs, sesion, c.get(4));
            case "F" -> new Fin(seq, epochMs, sesion, c.get(4), c.get(5));
            case "P" -> new Perdida(seq, epochMs, sesion, numero(c.get(4), "cuantos"));
            default -> throw new AssertionError(tipo);
        };
    }

    private static long numero(String valor, String campo) {
        try {
            return Long.parseLong(valor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("el campo " + campo + " no es un número: " + valor, e);
        }
    }

    static String cabecera(String generacion) {
        return PREFIJO_CABECERA + "\t" + VERSION + "\t" + generacion;
    }

    static boolean esCabecera(String linea) {
        return linea.startsWith(PREFIJO_CABECERA);
    }

    /** La generación de una cabecera nuestra y de esta versión; lanza si no lo es. */
    static String generacionDe(String linea) {
        String[] c = linea.split("\t", -1);
        if (c.length != 3 || !c[0].equals(PREFIJO_CABECERA)) {
            throw new IllegalArgumentException("no es la cabecera de un registro de la consola");
        }
        if (!c[1].equals(Integer.toString(VERSION))) {
            throw new IllegalArgumentException("registro de la consola en versión " + c[1] + ": esta ventana solo entiende la " + VERSION);
        }
        if (c[2].isEmpty()) throw new IllegalArgumentException("cabecera sin generación");
        return c[2];
    }
}
