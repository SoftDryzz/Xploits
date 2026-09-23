package com.xploits.console.core;

/** El nivel de un mensaje, con su letra en {@code vivo.log} y su palabra en el historial. */
public enum Nivel {
    INFO('I', "INFO"),
    AVISO('W', "AVISO"),
    ERROR('E', "ERROR");

    private final char codigo;
    private final String etiqueta;

    Nivel(char codigo, String etiqueta) {
        this.codigo = codigo;
        this.etiqueta = etiqueta;
    }

    public char codigo() {
        return codigo;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public static Nivel de(char codigo) {
        for (Nivel n : values()) {
            if (n.codigo == codigo) return n;
        }
        throw new IllegalArgumentException("nivel desconocido: " + codigo);
    }
}
