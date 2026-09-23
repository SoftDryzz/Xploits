package com.xploits.console.core;

import java.util.OptionalLong;

/**
 * Los mensajes que no cupieron en la cola del escritor. El primero se anuncia en el juego, una sola
 * vez; los demás se cuentan y se escriben como un registro {@code P} cuando la cola se vacía.
 *
 * <p>Se llama desde dos hilos -el del juego descarta, el escritor drena-, de ahí los
 * {@code synchronized}.
 */
public final class Perdidas {
    private long cuenta;
    private boolean anunciada;

    /** Cuenta uno. Devuelve true solo la primera vez: es cuando hay que avisar. */
    public synchronized boolean descartado() {
        cuenta++;
        if (anunciada) return false;
        anunciada = true;
        return true;
    }

    /** Si hay perdidos sin anotar, cuántos, y vuelve a cero. */
    public synchronized OptionalLong drenar() {
        if (cuenta == 0) return OptionalLong.empty();
        long n = cuenta;
        cuenta = 0;
        return OptionalLong.of(n);
    }
}
