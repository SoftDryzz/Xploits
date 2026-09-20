package com.xploits.travel.core;

/**
 * La decisión del aviso de fuegos artificiales (spec AutoTravel §8: <i>"Sin fuegos a mitad de vuelo
 * → Aviso fuerte: toast y sonido. Enterarse a 100k importa"</i>). Aquí vive todo lo que se puede
 * decidir sin tocar Minecraft: a partir de cuántos fuegos se avisa y cuándo se repite el aviso.
 * Contar los fuegos del inventario y sacar el toast es del adaptador.
 *
 * <p><b>Por qué hace falta una clase para un {@code if}.</b> El vuelo se observa una vez por tick,
 * así que un aviso sin memoria saldría veinte veces por segundo hasta el final del viaje y taparía
 * cualquier otra cosa en pantalla. Y la memoria ingenua -un {@code boolean} que se pone a
 * {@code true} y no se quita- tiene el fallo contrario: si el jugador repone fuegos de un shulker y
 * más tarde vuelve a quedarse sin ellos, el módulo se quedaría mudo el resto del viaje, que es
 * justo cuando el aviso importa más.
 *
 * <p>La salida es una histéresis con una sola frontera: se avisa al entrar en la banda de peligro
 * ({@code fuegos <= umbral}) y el aviso <b>se rearma</b> al salir de ella ({@code fuegos > umbral}).
 * Durante el vuelo la cuenta solo baja -cada impulso gasta uno-, así que salir de la banda significa
 * exactamente una cosa: que se han repuesto.
 */
public final class FireworkWatch {
    private final int threshold;

    /** Si ya se avisó y todavía no se ha salido de la banda de peligro. */
    private boolean warned;

    /**
     * @param threshold con estos fuegos o menos se avisa. Cero significa avisar solo cuando se
     *                  acaben del todo; cualquier valor mayor avisa con margen, que es de lo que se
     *                  trata a cien mil bloques de casa
     */
    public FireworkWatch(int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("el umbral de fuegos no puede ser negativo: " + threshold);
        }
        this.threshold = threshold;
    }

    /**
     * Observa la cuenta de fuegos de este tick y dice si hay que sacar el aviso fuerte ahora.
     *
     * @return {@code true} una sola vez por cada entrada en la banda de peligro
     */
    public boolean observe(int fireworks) {
        if (fireworks > threshold) {
            // Fuera de la banda: se han repuesto, o nunca se entró. El aviso vuelve a estar armado.
            warned = false;
            return false;
        }
        if (warned) return false;
        warned = true;
        return true;
    }

    /** Vuelve al estado de recién empezada, con el aviso armado. Para el arranque de un viaje. */
    public void reset() {
        warned = false;
    }

    public int threshold() {
        return threshold;
    }
}
