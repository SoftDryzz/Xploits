package com.xploits.console.core;

import java.util.IllegalFormatException;

/**
 * El {@code String.format} de las clases base (spec consola §11). Meteor formatea el mensaje antes
 * de mandarlo al chat; si la plantilla está rota, lanza y el mensaje se pierde. Aquí se formatea
 * igual —mismo método, mismo locale por defecto— pero un formato roto no se come el mensaje: se
 * registra la plantilla cruda, en ERROR y diciendo qué pasó.
 */
public final class Formato {
    private Formato() {
    }

    public record Resultado(String texto, boolean roto) {
    }

    public static Resultado aplicar(String plantilla, Object... args) {
        try {
            return new Resultado(String.format(plantilla, args), false);
        } catch (IllegalFormatException e) {
            return new Resultado(plantilla + " [formato roto: " + e.getClass().getSimpleName() + "]", true);
        }
    }
}
