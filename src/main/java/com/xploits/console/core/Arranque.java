package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * La orden que abre la ventana (spec consola §3.1 y §8), verificada lanzándola desde {@code javaw}:
 * {@code cmd /c start "<título>" cmd /c "chcp 65001 & java …"}.
 *
 * <p>Dos trampas de {@code cmd}, las dos comprobadas: un título sin espacio lo toma {@code start}
 * por el programa, y los caracteres {@code & | < > ^ % ! "} en una ruta los interpreta la consola.
 * Lo segundo no se puede escapar con garantías, así que se rechaza nombrando la ruta.
 */
public final class Arranque {
    public static final String TITULO = "Xploits consola"; // i18n: allowed: the Windows window title the launch uses; kept stable, not player text
    public static final String CLASE = "com.xploits.console.ventana.ConsoleMain";
    private static final String PROHIBIDOS = "&|<>^%!\"";

    private Arranque() {
    }

    public sealed interface Resultado {
    }

    /** {@code descripcion} es la orden interna tal cual, para decírsela al jugador si la ventana no llega. */
    public record Orden(List<String> argv, String descripcion) implements Resultado {
    }

    public record Rechazo(Msg motivo) implements Resultado {
    }

    public static Resultado preparar(Path java, boolean javaExiste, List<Path> classpath, Path carpeta,
                                     long pidJuego, String lanzamiento, String sesion, Language idioma) {
        if (!lanzamiento.matches("[0-9a-z]+")) throw new IllegalArgumentException("id de lanzamiento no válido: " + lanzamiento);
        if (!sesion.matches("[0-9a-z]+")) throw new IllegalArgumentException("id de sesión no válido: " + sesion);
        if (!javaExiste) return new Rechazo(Msg.of(ConsoleText.NO_JAVA, "path", java.toString()));
        if (classpath.isEmpty()) return new Rechazo(Msg.of(ConsoleText.NO_JAR));
        List<Path> rutas = new ArrayList<>();
        rutas.add(java);
        rutas.addAll(classpath);
        rutas.add(carpeta);
        for (Path ruta : rutas) {
            String texto = ruta.toString();
            for (char c : PROHIBIDOS.toCharArray()) {
                if (texto.indexOf(c) >= 0) {
                    return new Rechazo(Msg.of(ConsoleText.BAD_PATH_CHARACTER, "path", texto, "character", String.valueOf(c)));
                }
            }
        }
        String cp = classpath.stream().map(Path::toString).collect(Collectors.joining(";"));
        String interna = "chcp 65001 >nul & \"" + java + "\" -cp \"" + cp + "\" " + CLASE
            + " \"" + carpeta + "\" " + pidJuego + " " + lanzamiento + " " + sesion + " " + idioma.code();
        return new Orden(List.of("cmd.exe", "/c", "start", TITULO, "cmd.exe", "/c", interna), interna);
    }
}
