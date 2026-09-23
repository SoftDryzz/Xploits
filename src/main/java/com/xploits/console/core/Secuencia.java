package com.xploits.console.core;

/**
 * Detecta registros perdidos: dentro de una sesión del juego, {@code seq} crece de uno en uno.
 * Retroceder es un fallo de lectura, no un hueco, y se dice en voz alta.
 */
public final class Secuencia {
    private String sesion;
    private long ultima;

    /** Cuántos registros faltan entre el anterior de la misma sesión y este. Una sesión nueva empieza de cero. */
    public long hueco(String sesion, long seq) {
        if (!sesion.equals(this.sesion)) {
            this.sesion = sesion;
            ultima = seq;
            return 0;
        }
        if (seq <= ultima) {
            throw new IllegalStateException("registro fuera de orden: el " + seq + " llega después del " + ultima);
        }
        long perdidos = seq - ultima - 1;
        ultima = seq;
        return perdidos;
    }
}
