package com.xploits.travel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcula la ruta de un viaje: la secuencia de waypoints que llevan de {@code origin} al destino
 * siguiendo el patrón de despiste elegido (spec AutoTravel).
 *
 * <p>Toda la geometría vive aquí, en bloques de Minecraft sobre el plano XZ, sin saber nada de
 * Baritone ni del mundo real: eso es cosa de {@link BaritoneScript} y del adaptador.
 */
public final class RoutePlanner {
    /** Pasos de la espiral final de ESPIRAL. Fijo, no configurable (spec AutoTravel). */
    private static final int SPIRAL_STEPS = 36;
    /** Por debajo de esta distancia, origen y destino se consideran el mismo punto. */
    private static final double SAME_POINT_TOLERANCE = 1e-9;

    private RoutePlanner() {
    }

    /**
     * Planea el viaje de {@code origin} a {@code destination} con el patrón {@code pattern}.
     *
     * @param highwayMaxAmplitude el ancho máximo del corredor permitido cuando el destino es de
     *                            autopista; fuera de autopista no se usa
     * @return la ruta, o un rechazo si el patrón no es compatible con el destino pedido
     */
    public static Route plan(Waypoint origin, Destination destination, FlightPattern pattern,
                              PatternParams params, double highwayMaxAmplitude) {
        if (pattern == FlightPattern.SENUELO && destination.highway()) {
            return Route.rejected("el señuelo se saldría del corredor de la autopista, se rechaza en vez de degradarse");
        }

        Waypoint destinationPoint = destination.resolve(origin);
        double distance = origin.distanceTo(destinationPoint);
        if (distance < SAME_POINT_TOLERANCE) {
            return Route.of(List.of(destinationPoint));
        }

        double ux = (destinationPoint.x() - origin.x()) / distance;
        double uz = (destinationPoint.z() - origin.z()) / distance;
        double nx = -uz;
        double nz = ux;

        return switch (pattern) {
            case RECTO -> Route.of(List.of(destinationPoint));
            case ZIGZAG -> {
                double amplitude = effectiveAmplitude(params.amplitude(), destination, highwayMaxAmplitude);
                yield Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.period(), amplitude));
            }
            case QUIEBRO -> {
                double amplitude = effectiveAmplitude(params.lateralOffset(), destination, highwayMaxAmplitude);
                yield Route.of(zigzag(origin, destinationPoint, ux, uz, nx, nz, distance, params.legLength(), amplitude));
            }
            case ESPIRAL -> Route.of(spiral(destinationPoint, ux, uz, nx, nz, distance, params));
            case SENUELO -> Route.of(decoy(origin, destinationPoint, ux, uz, distance, params));
        };
    }

    /** El modo autopista acota la amplitud: fuera de autopista se usa la configurada sin tocar. */
    private static double effectiveAmplitude(double amplitude, Destination destination, double highwayMaxAmplitude) {
        return destination.highway() ? Math.min(amplitude, highwayMaxAmplitude) : amplitude;
    }

    /**
     * ZIGZAG y QUIEBRO comparten esta función; solo cambian el paso y la amplitud que reciben. Para
     * {@code i} de 1 a {@code floor(distance/period)}, el waypoint es {@code origen + u*(i*period) +
     * n*(amplitude * (i impar ? +1 : -1))}. Al final, siempre el destino exacto.
     */
    private static List<Waypoint> zigzag(Waypoint origin, Waypoint destination, double ux, double uz,
                                          double nx, double nz, double distance, double period, double amplitude) {
        List<Waypoint> points = new ArrayList<>();
        int steps = (int) Math.floor(distance / period);
        for (int i = 1; i <= steps; i++) {
            double along = i * period;
            double sign = (i % 2 == 1) ? 1 : -1;
            double x = origin.x() + ux * along + nx * amplitude * sign;
            double z = origin.z() + uz * along + nz * amplitude * sign;
            points.add(new Waypoint(x, z));
        }
        points.add(destination);
        return points;
    }

    /**
     * El tramo final mide {@code 2*radius}, acotado a {@code distance/2} si el viaje es más corto:
     * así la espiral nunca retrocede detrás del origen. Se va recto hasta {@code destino -
     * u*(2*radius)} y desde ahí se dan {@code spiralTurns} vueltas cerrándose sobre el destino, en
     * {@link #SPIRAL_STEPS} pasos, con el ángulo medido desde la dirección {@code -u} hacia {@code
     * n}.
     */
    private static List<Waypoint> spiral(Waypoint destination, double ux, double uz, double nx, double nz,
                                          double distance, PatternParams params) {
        double radius = Math.min(params.spiralRadius(), distance / 2.0);

        List<Waypoint> points = new ArrayList<>();
        points.add(new Waypoint(destination.x() - ux * 2 * radius, destination.z() - uz * 2 * radius));

        for (int j = 0; j <= SPIRAL_STEPS; j++) {
            double fraction = (double) j / SPIRAL_STEPS;
            double angle = 2 * Math.PI * params.spiralTurns() * fraction;
            double stepRadius = radius * (1 - fraction);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // Base (-u, n): ángulo 0 apunta hacia -u, y crece girando hacia n.
            double x = destination.x() + stepRadius * (cos * -ux + sin * nx);
            double z = destination.z() + stepRadius * (cos * -uz + sin * nz);
            points.add(new Waypoint(x, z));
        }
        return points;
    }

    /**
     * El señuelo es {@code origen + rotar(u, grados)*distancia}. El punto de corrección -el único
     * waypoint intermedio- es {@code origen + (señuelo - origen)*fracción}. Waypoints: el punto de
     * corrección, y el destino.
     */
    private static List<Waypoint> decoy(Waypoint origin, Waypoint destination, double ux, double uz,
                                         double distance, PatternParams params) {
        double radians = Math.toRadians(params.decoyAngleDegrees());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double decoyDirectionX = ux * cos - uz * sin;
        double decoyDirectionZ = ux * sin + uz * cos;

        double correctionX = origin.x() + decoyDirectionX * distance * params.decoyFraction();
        double correctionZ = origin.z() + decoyDirectionZ * distance * params.decoyFraction();

        return List.of(new Waypoint(correctionX, correctionZ), destination);
    }
}
