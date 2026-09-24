package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

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
    public static String linea(Registro r, ZoneId zona, Catalog textos) {
        String fecha = FECHA.format(Instant.ofEpochMilli(r.epochMs()).atZone(zona));
        return switch (r) {
            case Registro.Mensaje m -> fecha + "  " + rellenar(m.nivel().etiqueta(textos)) + "  " + m.fuente() + "  " + cuerpo(m.texto());
            case Registro.Juego j -> fecha + "  " + rellenar(textos.render(ConsoleText.HISTORY_GAME)) + "  "
                + cuerpo(motivo(j.motivo(), textos));
            case Registro.Perdida p -> fecha + "  " + rellenar(Nivel.AVISO.etiqueta(textos)) + "  consola  "
                + textos.render(ConsoleText.HISTORY_LOST, "count", p.cuantos());
            case Registro.Foto f -> null;
            case Registro.Fin f -> null;
        };
    }

    /** {@code inicio} and {@code fin} are protocol words in vivo.log; the history says them in its language. */
    private static String motivo(String motivo, Catalog textos) {
        return switch (motivo) {
            case "inicio" -> textos.render(ConsoleText.GAME_START);
            case "fin" -> textos.render(ConsoleText.GAME_END);
            default -> motivo;
        };
    }

    private static String rellenar(String etiqueta) {
        return etiqueta + " ".repeat(Math.max(0, 5 - etiqueta.length()));
    }

    private static String cuerpo(String texto) {
        return Texto.limpiar(texto).replace("\n", "\n" + SANGRIA);
    }
}
