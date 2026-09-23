package com.xploits.console.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Una línea del historial legible (spec consola §5): fecha con milisegundos y desfase -para que sea
 * inequívoca aunque cambie la hora-, nivel, fuente y texto; las líneas de continuación, sangradas.
 */
public final class Historial {
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx");
    private static final String SANGRIA = "    ";

    private Historial() {
    }

    /** La línea de un registro, o null si no va al historial (las fotos y las órdenes de cierre). */
    public static String linea(Registro r, ZoneId zona) {
        String fecha = FECHA.format(Instant.ofEpochMilli(r.epochMs()).atZone(zona));
        return switch (r) {
            case Registro.Mensaje m -> fecha + "  " + rellenar(m.nivel().etiqueta()) + "  " + m.fuente() + "  " + cuerpo(m.texto());
            case Registro.Juego j -> fecha + "  JUEGO  " + cuerpo(j.motivo());
            case Registro.Perdida p -> fecha + "  AVISO  consola  se perdieron " + p.cuantos()
                + " mensajes: la cola de la consola se llenó";
            case Registro.Foto f -> null;
            case Registro.Fin f -> null;
        };
    }

    private static String rellenar(String etiqueta) {
        return etiqueta + " ".repeat(Math.max(0, 5 - etiqueta.length()));
    }

    private static String cuerpo(String texto) {
        return Texto.limpiar(texto).replace("\n", "\n" + SANGRIA);
    }
}
