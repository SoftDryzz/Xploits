package com.xploits.console.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * La última red contra las coordenadas (spec consola §7). Los sitios que las llevan se marcan en el
 * origen; esto existe por si alguno se escapa sin marcar.
 *
 * <p>Falla cerrado y con patrones precisos: un falso positivo cuesta una línea oculta, que además
 * dice que lo está, y un falso negativo cuesta la base del jugador.
 */
public final class Centinela {
    private static final List<Pattern> PATRONES = List.of(
        // El formato de ContainerKey.id(): dimension@x,y,z.
        Pattern.compile("@-?\\d+,-?\\d+,-?\\d+"),
        // Un goal o goto de Baritone con dos o tres enteros, con cualquier prefijo o sin él.
        Pattern.compile("(?<![\\p{L}\\p{N}])[^\\s\\p{L}\\p{N}]?(?:goal|goto)\\s+-?\\d+(?:\\s+-?\\d+){1,2}(?!\\d)",
            Pattern.CASE_INSENSITIVE),
        // El fragmento antiguo de AutoTravel.status(): "en 1200, -800".
        Pattern.compile("(?<![\\p{L}\\p{N}])en -?\\d{3,}, -?\\d{3,}(?!\\d)"),
        // Su equivalente en inglés: "at 1200, -800".
        Pattern.compile("(?<![\\p{L}\\p{N}])at -?\\d{3,}, -?\\d{3,}(?!\\d)"));

    private Centinela() {
    }

    /** Si el texto tiene pinta de llevar coordenadas. */
    public static boolean sospecha(String texto) {
        for (Pattern patron : PATRONES) {
            if (patron.matcher(texto).find()) return true;
        }
        return false;
    }

    /** Lo que queda de un texto retenido: que se retuvo y de quién venía, nada más. */
    public static String retenido(String fuente) {
        return "[retenido: parecía llevar coordenadas · fuente " + fuente + "]";
    }

    /** El texto que se puede escribir, y si hubo que retenerlo. */
    public record Veredicto(String texto, boolean retenido) {
    }

    public static Veredicto revisar(String fuente, String texto) {
        return sospecha(texto) ? new Veredicto(retenido(fuente), true) : new Veredicto(texto, false);
    }
}
