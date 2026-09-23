package com.xploits.shared.core;

import java.util.Objects;

/**
 * Un mensaje en sus dos versiones: la del chat, que puede llevar coordenadas, y la del registro
 * -consola y fichero-, que no puede llevarlas nunca (spec consola §7).
 *
 * <p>Se marca en el origen porque solo el origen sabe qué lleva: una expresión regular en el
 * sumidero no distingue {@code destino 1200, -800} de {@code 3, 4 bloques}, y fallaría en los dos
 * sentidos.
 */
public record TextoConPosicion(String chat, String registro) {
    public TextoConPosicion {
        Objects.requireNonNull(chat, "el texto del chat no puede ser nulo");
        Objects.requireNonNull(registro, "el texto del registro no puede ser nulo");
    }

    /** Un mensaje sin posición: el mismo texto en el chat y en el registro. */
    public static TextoConPosicion igual(String texto) {
        return new TextoConPosicion(texto, texto);
    }
}
