package com.xploits.console.core;

/**
 * Los caracteres del marco. Los de caja se ven bien en la ventana con UTF-8 (spec §3.1); si la
 * consola no está en UTF-8 se dibuja en ASCII y se dice.
 */
public enum Glifos {
    UNICODE('─', "●", "○"),
    ASCII('-', "*", "o");

    private final char horizontal;
    private final String activo;
    private final String inactivo;

    Glifos(char horizontal, String activo, String inactivo) {
        this.horizontal = horizontal;
        this.activo = activo;
        this.inactivo = inactivo;
    }

    public char horizontal() {
        return horizontal;
    }

    public String activo() {
        return activo;
    }

    public String inactivo() {
        return inactivo;
    }

    public String linea(int cols) {
        return String.valueOf(horizontal).repeat(Math.max(0, cols));
    }
}
