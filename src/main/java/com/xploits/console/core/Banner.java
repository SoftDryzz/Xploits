package com.xploits.console.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * El logo XTO2002 (spec consola §15). Se pinta a tamaño completo o no se pinta: escalado, el trazo
 * cae por debajo del umbral de rasterizado y las letras se rompen (verificado en capturas).
 */
public final class Banner {
    public static final String RECURSO = "/xploits/consola/logo.ans";
    public static final int FILAS = 13;
    public static final int ANCHO_MAXIMO = 100;
    public static final int COLUMNAS_MINIMAS = 100;
    public static final int FILAS_MINIMAS = 40;
    public static final String TEXTO = "XTO2002";
    private static final Pattern COLOR = Pattern.compile("\u001b\\[[0-9;]*m");

    private Banner() {
    }

    public static List<String> cargar() throws IOException {
        try (InputStream in = Banner.class.getResourceAsStream(RECURSO)) {
            if (in == null) throw new IOException("falta el recurso " + RECURSO + " en el jar");
            List<String> lineas = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            validar(lineas);
            return lineas;
        }
    }

    /** Lanza si el logo no es lo que se espera: nunca se pinta un logo roto como si fuera el bueno. */
    public static void validar(List<String> arte) {
        if (arte.size() != FILAS) {
            throw new IllegalArgumentException("el logo tiene que tener " + FILAS + " filas y tiene " + arte.size());
        }
        for (int i = 0; i < arte.size(); i++) {
            String linea = arte.get(i);
            String sinColor = COLOR.matcher(linea).replaceAll("");
            if (sinColor.indexOf('\u001b') >= 0) {
                throw new IllegalArgumentException("la fila " + (i + 1) + " del logo lleva un escape que no es de color");
            }
            if (!linea.endsWith(Ansi.RESET)) {
                throw new IllegalArgumentException("la fila " + (i + 1) + " del logo no acaba en ESC[0m: el color se escaparía a la siguiente");
            }
            int ancho = Texto.ancho(sinColor);
            if (ancho > ANCHO_MAXIMO) {
                throw new IllegalArgumentException("la fila " + (i + 1) + " del logo mide " + ancho + " columnas y el máximo es " + ANCHO_MAXIMO);
            }
        }
    }

    public static int anchoVisible(String linea) {
        return Texto.ancho(Ansi.sinColor(linea));
    }

    public static boolean cabe(int cols, int filas) {
        return cols >= COLUMNAS_MINIMAS && filas >= FILAS_MINIMAS;
    }

    /** El logo si la ventana da para él; si no, el nombre en una fila, en el cian del logo. */
    public static List<String> elegir(List<String> arte, int cols, int filas) {
        if (cabe(cols, filas)) return arte;
        return List.of(Ansi.color(Ansi.CIAN) + TEXTO + Ansi.RESET);
    }
}
