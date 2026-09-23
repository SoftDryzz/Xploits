package com.xploits.travel.core;

/**
 * A dónde quiere ir el jugador (spec AutoTravel). Hay tres formas de pedirlo:
 *
 * <ul>
 *   <li>{@link #coordinates(double, double)}: un punto absoluto del mundo, en X y Z.</li>
 *   <li>{@link #relative(double, double)}: un <b>desplazamiento</b> en X y en Z desde donde arranque
 *       el viaje. Poner 5000 y -3000 es "muévete 5000 en X y -3000 en Z desde aquí".</li>
 *   <li>{@link #highway(Axis, double)}: una distancia a recorrer a lo largo de un eje, medida
 *       desde el punto de partida del viaje -no desde el origen del mundo-, como corresponde a
 *       pedir "tantos bloques por la autopista de +X" sin conocer las coordenadas exactas.</li>
 * </ul>
 *
 * <p>Resolver un destino es aritmética pura -un punto de partida y unos números-, así que vive aquí
 * y se prueba sin arrancar el juego; el adaptador solo pone el origen.
 *
 * <p>{@link Kind#AUTOPISTA} es la marca que distingue el modo autopista: en él el señuelo se rechaza
 * en vez de degradarse, porque sacaría al jugador del corredor. {@link #highway()} es lo que mira
 * {@link RoutePlanner}, y sigue siendo una pregunta de sí o no.
 *
 * <p><b>Las tres formas son tres campos distintos y no uno reinterpretado</b>, y eso también es
 * deliberado aguas arriba, en los ajustes del módulo: si {@code x}/{@code z} sirvieran para las
 * coordenadas absolutas y para el desplazamiento, cambiar de modo convertiría un destino lejano ya
 * configurado en un desplazamiento enorme desde donde estés, sin tocar un solo número. El viaje
 * resultante no se parece en nada al que pedía cualquiera de los dos significados.
 *
 * <p><b>Un desplazamiento de (0, 0) es el destino igual al origen</b>, y eso no se rechaza: cae en
 * el mismo camino que un destino por coordenadas que coincide con la posición del jugador, o que
 * una distancia de autopista de 0, y el planificador ya devuelve ahí una ruta de un solo waypoint
 * -el propio punto de partida-. No es una degradación silenciosa: un viaje de cero bloques es
 * exactamente lo que se pidió, entregado tal cual, y no hay ningún patrón que se esté dibujando
 * recto a espaldas de nadie. Lo que sí se rechazaría es entregar otra cosa distinta callándolo.
 */
public record Destination(Kind kind, double x, double z, Axis axis, double distance) {
    /** Las tres formas de pedir un destino, como vocabulario del núcleo. */
    public enum Kind {
        /** Un punto absoluto del mundo. */
        COORDENADAS,
        /** Un desplazamiento en X y en Z desde el punto de partida del viaje. */
        RELATIVO,
        /** Un eje de autopista y una distancia a recorrer por él. */
        AUTOPISTA
    }

    public Destination {
        if (kind == null) {
            throw new IllegalArgumentException("un destino necesita una forma: kind no puede ser null");
        }
        if (kind == Kind.AUTOPISTA && axis == null) {
            throw new IllegalArgumentException("un destino de autopista necesita un eje: axis no puede ser null");
        }
    }

    /** Un punto absoluto del mundo. */
    public static Destination coordinates(double x, double z) {
        return new Destination(Kind.COORDENADAS, x, z, null, 0);
    }

    /** Un desplazamiento en X y en Z a partir del punto de partida del viaje. */
    public static Destination relative(double offsetX, double offsetZ) {
        return new Destination(Kind.RELATIVO, offsetX, offsetZ, null, 0);
    }

    /** Una distancia a recorrer por el eje dado, a partir del punto de partida del viaje. */
    public static Destination highway(Axis axis, double distance) {
        return new Destination(Kind.AUTOPISTA, 0, 0, axis, distance);
    }

    /**
     * Si el destino va pegado a un eje de autopista, que es lo que obliga al patrón a quedarse
     * dentro del corredor y lo que prohíbe el señuelo (spec §4.2).
     */
    public boolean highway() {
        return kind == Kind.AUTOPISTA;
    }

    /**
     * Resuelve este destino a un punto concreto, dado el origen del viaje.
     *
     * <p>En autopista el punto es {@code origen + unitario(eje) * distancia}. Con el vector unitario
     * -y no con el par de signos a secas- la distancia son <b>bloques recorridos</b> en los ocho
     * ejes: por una diagonal, N bloques avanzan {@code N/√2} en cada coordenada y el vuelo mide N.
     * Los cuatro cardinales tienen unitario (±1, 0) o (0, ±1) exacto, así que resuelven al mismo
     * punto de siempre.
     */
    public Waypoint resolve(Waypoint origin) {
        return switch (kind) {
            case COORDENADAS -> new Waypoint(x, z);
            case RELATIVO -> new Waypoint(origin.x() + x, origin.z() + z);
            case AUTOPISTA -> new Waypoint(origin.x() + axis.unitX() * distance,
                origin.z() + axis.unitZ() * distance);
        };
    }
}
