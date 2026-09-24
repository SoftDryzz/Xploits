package com.xploits.console.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** El tamaño de la ventana en columnas y filas (spec consola §6). */
public record Tamano(int cols, int filas) {
    public static final Tamano PEDIDO = new Tamano(110, 46);
    public static final Tamano MINIMO = new Tamano(60, 12);
    private static final Pattern NUMERO = Pattern.compile("\\d{1,5}");

    public boolean cabeElMinimo() {
        return cols >= MINIMO.cols && filas >= MINIMO.filas;
    }

    /**
     * Lee la salida de {@code mode con}, que viene traducida al idioma de Windows: los dos primeros
     * números después de la línea de rayas son las filas y las columnas (verificado en español).
     */
    public static Optional<Tamano> deModeCon(String salida) {
        int raya = salida.indexOf("---");
        if (raya < 0) return Optional.empty();
        int finDeRaya = salida.indexOf('\n', raya);
        if (finDeRaya < 0) return Optional.empty();
        Matcher m = NUMERO.matcher(salida.substring(finDeRaya));
        if (!m.find()) return Optional.empty();
        int filas = Integer.parseInt(m.group());
        if (!m.find()) return Optional.empty();
        int cols = Integer.parseInt(m.group());
        if (filas <= 0 || cols <= 0) return Optional.empty();
        return Optional.of(new Tamano(cols, filas));
    }
}
