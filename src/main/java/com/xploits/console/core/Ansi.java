package com.xploits.console.core;

import java.util.List;

/**
 * Las secuencias de escape de la ventana, en un solo sitio (spec consola §6). Todas verificadas en
 * Windows Terminal: el redimensionado, la región de scroll de una fila para la entrada y el
 * guardar/restaurar cursor alrededor de cada fotograma.
 */
public final class Ansi {
    public static final String ESC = "\u001b";
    public static final String CSI = ESC + "[";
    public static final String RESET = CSI + "0m";
    public static final String BORRAR_FIN = CSI + "K";
    public static final String BORRAR_PANTALLA = CSI + "2J";
    public static final String GUARDAR = ESC + "7";
    public static final String RESTAURAR = ESC + "8";
    public static final String SINCRONIZAR_INICIO = CSI + "?2026h";
    public static final String SINCRONIZAR_FIN = CSI + "?2026l";
    public static final String REGION_TODA = CSI + "r";

    public static final int CIAN = 45;
    public static final int MAGENTA = 199;
    public static final int BLANCO = 231;
    public static final int AMARILLO = 220;
    public static final int ROJO = 196;

    private Ansi() {
    }

    public static String irA(int fila, int col) {
        return CSI + fila + ";" + col + "H";
    }

    public static String tamano(int filas, int cols) {
        return CSI + "8;" + filas + ";" + cols + "t";
    }

    public static String region(int desde, int hasta) {
        return CSI + desde + ";" + hasta + "r";
    }

    public static String color(int n) {
        return CSI + "38;5;" + n + "m";
    }

    public static String titulo(String titulo) {
        return ESC + "]0;" + Texto.limpiar(titulo).replace('\n', ' ') + "\u0007";
    }

    /** Quita las secuencias de color de una línea, para medirla. */
    public static String sinColor(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    /**
     * Un fotograma entero, sincronizado y en una sola escritura: las filas de arriba abajo, sin la de
     * entrada. Con {@code reponerPrompt} se vuelve a escribir {@code "> "} en la fila de entrada (tras
     * un Enter); si no, el cursor vuelve a donde lo dejó el eco del teclado.
     *
     * <p>Nunca borra la pantalla entera: eso parpadea y además se llevaría lo tecleado.
     */
    public static String fotograma(List<String> filas, int filaEntrada, boolean reponerPrompt) {
        StringBuilder sb = new StringBuilder(SINCRONIZAR_INICIO).append(GUARDAR);
        for (int i = 0; i < filas.size(); i++) {
            sb.append(irA(i + 1, 1)).append(filas.get(i)).append(RESET).append(BORRAR_FIN);
        }
        if (reponerPrompt) sb.append(irA(filaEntrada, 1)).append("> ").append(BORRAR_FIN);
        else sb.append(RESTAURAR);
        return sb.append(SINCRONIZAR_FIN).toString();
    }
}
