package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

/** El nivel de un mensaje, con su letra en {@code vivo.log} y su palabra en el historial. */
public enum Nivel {
    INFO('I', ConsoleText.LEVEL_INFO),
    AVISO('W', ConsoleText.LEVEL_WARNING),
    ERROR('E', ConsoleText.LEVEL_ERROR);

    private final char codigo;
    private final ConsoleText etiqueta;

    Nivel(char codigo, ConsoleText etiqueta) {
        this.codigo = codigo;
        this.etiqueta = etiqueta;
    }

    public char codigo() {
        return codigo;
    }

    public String etiqueta(Catalog textos) {
        return textos.render(etiqueta);
    }

    public static Nivel de(char codigo) {
        for (Nivel n : values()) {
            if (n.codigo == codigo) return n;
        }
        throw new IllegalArgumentException("nivel desconocido: " + codigo);
    }
}
