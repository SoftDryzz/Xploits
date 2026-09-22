package com.xploits.pvp.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * "El objetivo se aleja de forma sostenida" (rediseño §4.3), medido de manera que no oscile.
 *
 * <p>Hace falta porque {@code auto-web} y {@code crystal-aura} se pisan: el aura exige que el bloque
 * de encima del apoyo sea aire y {@code auto-web} telaraña exactamente esa casilla, así que
 * encendidos a la vez la telaraña le quita al aura su mejor posición de cristal a cambio de una
 * telaraña que el otro rompe a mano en medio segundo. La telaraña sirve para <b>impedir que se
 * vaya</b>, no mientras le pegas, y por eso hay que distinguir "se está yendo" de "se ha movido".
 *
 * <h2>Cómo se mide</h2>
 * No por la velocidad de un tick -el ruido se la come- sino por el <b>terreno ganado en una
 * ventana</b>: la distancia de ahora menos la de hace {@link #WINDOW_TICKS} ticks. Esa diferencia es
 * la separación neta, que es justo lo que importa: si los dos corréis a la misma velocidad, el otro
 * no se está yendo por mucho que se mueva.
 *
 * <p>Y el resultado lleva <b>banda muerta</b>, no un umbral pelado: se entra en "se aleja" con
 * {@link #START_GAIN} bloques ganados en la ventana y solo se sale cuando baja de
 * {@link #STOP_GAIN}. Con un único umbral, una diferencia que se quedara rondando el valor exacto
 * encendería y apagaría {@code auto-web} en ticks alternos, que es la oscilación que hay que
 * evitar; con dos, hacen falta {@link #START_GAIN} - {@link #STOP_GAIN} = 0,75 bloques de cambio en
 * la diferencia para que el resultado se dé la vuelta, y eso no lo produce ni el retroceso de un
 * golpe ni orbitar alrededor del enemigo.
 */
public final class RetreatWatch {
    /**
     * Ancho de la ventana, en ticks. Medio segundo: la misma unidad de tiempo de combate que usa
     * §9 ("medio segundo son 2-5 ciclos de cristal") y del orden de un ciclo de cristal completo.
     * Más corta, el ruido de un golpe domina la medida; más larga, la telaraña llega tarde a alguien
     * que ya se fue.
     */
    public static final int WINDOW_TICKS = 10;

    /**
     * Bloques de separación ganados en la ventana para declarar que se aleja. 1,0 bloque en medio
     * segundo son 2 bloques por segundo de separación neta, poco más de un tercio de la velocidad
     * de sprint (5,6 b/s): el retroceso de un golpe (unos 0,4 bloques) y orbitar alrededor del
     * enemigo mientras le cristaleas (±0,5) se quedan claramente por debajo, y en cambio lo alcanza
     * quien de verdad se marcha -y también quien te deja atrás porque a ti te han frenado-.
     */
    public static final double START_GAIN = 1.0;

    /**
     * Bloques ganados por debajo de los cuales se deja de considerar que se aleja. No es cero para
     * que el final tampoco parpadee: mientras siga ganando algo de terreno, sigue yéndose.
     */
    public static final double STOP_GAIN = 0.25;

    private final Deque<Double> distances = new ArrayDeque<>();
    private String targetId;
    private boolean retreating;

    /**
     * Añade la distancia de este tick y devuelve si el objetivo se aleja de forma sostenida.
     *
     * <p>Perder el objetivo o cambiar de objetivo borra la serie: comparar la distancia a uno con la
     * distancia a otro daría un salto enorme y una telaraña a nadie. El cambio se detecta por
     * {@link CombatSnapshot#targetId()}; mientras el adaptador no lo rellene, dos objetivos
     * distintos seguidos comparten serie y lo peor que puede pasar es que {@code auto-web} se
     * encienda de más durante media ventana, que es el lado barato del sesgo de §10.
     */
    public boolean update(CombatSnapshot snapshot) {
        if (!snapshot.hasTarget()) {
            reset();
            return false;
        }
        if (!Objects.equals(targetId, snapshot.targetId())) {
            reset();
            targetId = snapshot.targetId();
        }

        distances.addLast(snapshot.targetDistance());
        while (distances.size() > WINDOW_TICKS + 1) distances.removeFirst();
        // Hasta que la ventana no está llena no hay nada que comparar: no se afirma que se aleje
        // alguien al que se acaba de ver.
        if (distances.size() <= WINDOW_TICKS) return retreating;

        double gained = distances.getLast() - distances.getFirst();
        retreating = retreating ? gained > STOP_GAIN : gained >= START_GAIN;
        return retreating;
    }

    /** Si la última llamada a {@link #update} concluyó que se aleja. */
    public boolean retreating() {
        return retreating;
    }

    /** Olvida la serie entera. */
    public void reset() {
        distances.clear();
        targetId = null;
        retreating = false;
    }
}
