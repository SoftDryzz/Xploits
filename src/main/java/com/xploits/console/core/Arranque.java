package com.xploits.console.core;

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
    public static final String TITULO = "Xploits consola";
    public static final String CLASE = "com.xploits.console.ventana.ConsoleMain";
    private static final String PROHIBIDOS = "&|<>^%!\"";

    private Arranque() {
    }

    public sealed interface Resultado {
    }

    /** {@code descripcion} es la orden interna tal cual, para decírsela al jugador si la ventana no llega. */
    public record Orden(List<String> argv, String descripcion) implements Resultado {
    }

    public record Rechazo(String motivo) implements Resultado {
    }

    public static Resultado preparar(Path java, boolean javaExiste, List<Path> classpath, Path carpeta,
                                     long pidJuego, String lanzamiento) {
        if (!lanzamiento.matches("[0-9a-z]+")) throw new IllegalArgumentException("id de lanzamiento no válido: " + lanzamiento);
        if (!javaExiste) return new Rechazo("no encuentro java.exe en " + java + ", y sin él no se abre la ventana");
        if (classpath.isEmpty()) return new Rechazo("no encuentro el jar del addon en disco, y la ventana se ejecuta desde él");
        List<Path> rutas = new ArrayList<>();
        rutas.add(java);
        rutas.addAll(classpath);
        rutas.add(carpeta);
        for (Path ruta : rutas) {
            String texto = ruta.toString();
            for (char c : PROHIBIDOS.toCharArray()) {
                if (texto.indexOf(c) >= 0) {
                    return new Rechazo("la ruta «" + texto + "» lleva el carácter «" + c
                        + "», que la consola de Windows interpretaría: mueve la instancia a una carpeta sin él");
                }
            }
        }
        String cp = classpath.stream().map(Path::toString).collect(Collectors.joining(";"));
        String interna = "chcp 65001 >nul & \"" + java + "\" -cp \"" + cp + "\" " + CLASE
            + " \"" + carpeta + "\" " + pidJuego + " " + lanzamiento;
        return new Orden(List.of("cmd.exe", "/c", "start", TITULO, "cmd.exe", "/c", interna), interna);
    }
}
