package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

/**
 * El estado del juego visto desde la ventana (spec consola §8). Dos señales: las fotos, que el
 * juego escribe al menos una vez por segundo, y si su proceso sigue vivo. El PID manda: un latido
 * fresco de un proceso muerto es un fichero viejo, no un juego vivo.
 */
public final class Latido {
    public static final long LENTO_MS = 3_000;
    public static final long SIN_RESPUESTA_MS = 15_000;

    private Latido() {
    }

    public sealed interface EstadoJuego {
        record SinDatos() implements EstadoJuego {
        }

        record Vivo() implements EstadoJuego {
        }

        /** Cargar un mundo congela el hilo del juego unos segundos: amarillo, no rojo. */
        record Lento(long segundos) implements EstadoJuego {
        }

        record NoResponde(long segundos) implements EstadoJuego {
        }

        record Cerrado() implements EstadoJuego {
        }

        record CerradoSinDespedirse() implements EstadoJuego {
        }
    }

    public static EstadoJuego evaluar(Long ultimoLatidoMs, long ahoraMs, boolean pidVivo, boolean vistoFin) {
        if (!pidVivo) return vistoFin ? new EstadoJuego.Cerrado() : new EstadoJuego.CerradoSinDespedirse();
        if (ultimoLatidoMs == null) return new EstadoJuego.SinDatos();
        long edad = Math.max(0, ahoraMs - ultimoLatidoMs);
        if (edad < LENTO_MS) return new EstadoJuego.Vivo();
        if (edad <= SIN_RESPUESTA_MS) return new EstadoJuego.Lento(edad / 1000);
        return new EstadoJuego.NoResponde(edad / 1000);
    }

    public static String texto(EstadoJuego e, Catalog t) {
        return switch (e) {
            case EstadoJuego.SinDatos s -> t.render(WindowText.HEARTBEAT_WAITING);
            case EstadoJuego.Vivo v -> t.render(WindowText.HEARTBEAT_ALIVE);
            case EstadoJuego.Lento l -> t.render(WindowText.HEARTBEAT_SLOW, "seconds", l.segundos());
            case EstadoJuego.NoResponde n -> t.render(WindowText.HEARTBEAT_NOT_RESPONDING, "seconds", n.segundos());
            case EstadoJuego.Cerrado c -> t.render(WindowText.GAME_CLOSED);
            case EstadoJuego.CerradoSinDespedirse c -> t.render(WindowText.GAME_ENDED_WITHOUT_GOODBYE);
        };
    }

    /** El color de la línea de estado; 0 es sin color. */
    public static int color(EstadoJuego e) {
        return switch (e) {
            case EstadoJuego.Lento l -> Ansi.AMARILLO;
            case EstadoJuego.NoResponde n -> Ansi.ROJO;
            case EstadoJuego.CerradoSinDespedirse c -> Ansi.ROJO;
            default -> 0;
        };
    }
}
