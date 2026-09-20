package com.xploits.travel.core;

/**
 * A dónde quiere ir el jugador (spec AutoTravel). Hay dos formas de pedirlo:
 *
 * <ul>
 *   <li>{@link #coordinates(double, double)}: un punto absoluto del mundo, en X y Z.</li>
 *   <li>{@link #highway(Axis, double)}: una distancia a recorrer a lo largo de un eje, medida
 *       desde el punto de partida del viaje -no desde el origen del mundo-, como corresponde a
 *       pedir "tantos bloques por la autopista de +X" sin conocer las coordenadas exactas.</li>
 * </ul>
 *
 * <p>{@code highway} es la marca que distingue ambos modos: cuando es {@code true}, el señuelo se
 * rechaza en vez de degradarse, porque sacaría al jugador del corredor de la autopista.
 */
public record Destination(boolean highway, double x, double z, Axis axis, double distance) {
    /** Un punto absoluto del mundo. */
    public static Destination coordinates(double x, double z) {
        return new Destination(false, x, z, null, 0);
    }

    /** Una distancia a recorrer por el eje dado, a partir del punto de partida del viaje. */
    public static Destination highway(Axis axis, double distance) {
        return new Destination(true, 0, 0, axis, distance);
    }

    /** Resuelve este destino a un punto concreto, dado el origen del viaje. */
    public Waypoint resolve(Waypoint origin) {
        if (!highway) return new Waypoint(x, z);
        return switch (axis) {
            case X_PLUS -> new Waypoint(origin.x() + distance, origin.z());
            case X_MINUS -> new Waypoint(origin.x() - distance, origin.z());
            case Z_PLUS -> new Waypoint(origin.x(), origin.z() + distance);
            case Z_MINUS -> new Waypoint(origin.x(), origin.z() - distance);
        };
    }
}
