package com.xploits.travel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcula la ruta de un viaje: la secuencia de waypoints que llevan de {@code origin} al destino
 * siguiendo el patrón de despiste elegido (spec AutoTravel).
 *
 * <p>Toda la geometría vive aquí, en bloques de Minecraft sobre el plano XZ, sin saber nada de
 * Baritone ni del mundo real: eso es cosa de {@link BaritoneScript} y del adaptador.
 *
 * <p>Ningún patrón saca al jugador del corredor de una autopista (spec §4.2): en ZIGZAG y QUIEBRO
 * se acota la amplitud, en ESPIRAL se acota el radio, y SENUELO -que perdería su razón de ser si
 * se acotara, un señuelo que no puede apuntar fuera del eje no es nada- se rechaza en vez de
 * degradarse. Se acota lo que sigue funcionando acotado; se rechaza lo que no.
 */
public final class RoutePlanner {
    /**
     * Tope de waypoints que puede generar un patrón ZIGZAG o QUIEBRO. Guarda contra parámetros
     * degenerados (un paso de 1 bloque en un viaje de 100 000, por ejemplo) que generarían cientos
     * de miles de puntos: el adaptador tendría que emitirlos uno a uno al chat de Baritone, así que
     * un número tan grande es en la práctica un cuelgue del cliente, no una ruta utilizable. 500
     * waypoints ya son muchísimos más de los que cualquier configuración razonable produce (con los
     * valores de fábrica, un viaje de 100 000 bloques genera 50).
     */
    public static final int MAX_PATTERN_WAYPOINTS = 500;

    /**
     * Pasos de espiral por cada vuelta completa, fijado para que el ajuste de fábrica
     * ({@code spiralTurns = 1.5}) siga dando exactamente 36 pasos. Los pasos escalan con las vueltas
     * configuradas -en vez de quedarse fijos en 36- porque, si no, más vueltas significan más grados
     * por paso: con 3 vueltas y 36 pasos fijos el paso angular llega a 30°, y la "espiral" se ve
     * como un polígono estrellado en vez de una curva.
     */
    private static final double SPIRAL_STEPS_PER_TURN = 24.0;
    /** Un mínimo de pasos para que unas pocas vueltas no degeneren en un triángulo. */
    private static final int MIN_SPIRAL_STEPS = 8;

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
            case ESPIRAL -> {
                double radiusCap = destination.highway() ? highwayMaxAmplitude : Double.POSITIVE_INFINITY;
                yield Route.of(spiral(destinationPoint, ux, uz, nx, nz, distance, params, radiusCap));
            }
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
     *
     * <p>Un paso de cero o negativo no tiene sentido -{@code floor(distancia/paso)} explotaría a
     * miles de millones de iteraciones- así que se trata como RECTO. Un paso positivo pero minúsculo
     * frente a la distancia se acota a {@link #MAX_PATTERN_WAYPOINTS}.
     */
    private static List<Waypoint> zigzag(Waypoint origin, Waypoint destination, double ux, double uz,
                                          double nx, double nz, double distance, double period, double amplitude) {
        if (period <= 0) return List.of(destination);

        List<Waypoint> points = new ArrayList<>();
        int steps = (int) Math.min(Math.floor(distance / period), MAX_PATTERN_WAYPOINTS);
        for (int i = 1; i <= steps; i++) {
            double along = i * period;
            double sign = (i % 2 == 1) ? 1 : -1;
            double x = origin.x() + ux * along + nx * amplitude * sign;
            double z = origin.z() + uz * along + nz * amplitude * sign;
            points.add(new Waypoint(x, z));
        }

        // El último punto lateral es un desvío en balde si ya cae a la altura del destino: te
        // aparta la amplitud entera sin ganar ningún avance, justo al final del viaje, que es donde
        // menos fuegos artificiales quedan. Se omite cuando lo que falta por recorrer en línea recta
        // es menor que la propia amplitud del desvío.
        if (!points.isEmpty() && distance - steps * period < amplitude) {
            points.remove(points.size() - 1);
        }

        points.add(destination);
        return points;
    }

    /**
     * El radio se acota a {@code distance/2} si el viaje es más corto -así la espiral nunca
     * retrocede detrás del origen- y, en autopista, también a {@code radiusCap}
     * ({@code highwayMaxAmplitude}): el punto más alejado del eje de cualquier paso está a lo sumo a
     * {@code stepRadius} del destino, y {@code stepRadius <= radius} siempre, así que acotar el
     * radio de partida basta para que ningún paso de la espiral se salga del corredor.
     *
     * <p>Se va recto hasta {@code destino - u*radius} -que es exactamente el primer punto de la
     * espiral, con {@code j=0}- y desde ahí se dan {@code spiralTurns} vueltas cerrándose sobre el
     * destino, con el ángulo medido desde la dirección {@code -u} hacia {@code n}: en {@code j=0}
     * eso da {@code destino - u*radius} en componentes, no solo en distancia, y es lo que garantiza
     * que el primer punto queda ANTES del destino y no después -medir desde {@code +u} pondría ese
     * mismo punto una radio PASADO el destino.
     */
    private static List<Waypoint> spiral(Waypoint destination, double ux, double uz, double nx, double nz,
                                          double distance, PatternParams params, double radiusCap) {
        double radius = Math.min(Math.min(params.spiralRadius(), distance / 2.0), radiusCap);
        int steps = spiralSteps(params.spiralTurns());

        List<Waypoint> points = new ArrayList<>();
        for (int j = 0; j <= steps; j++) {
            double fraction = (double) j / steps;
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

    /** Pasos de espiral escalados con las vueltas, con un mínimo para no degenerar en un polígono. */
    private static int spiralSteps(double turns) {
        return (int) Math.max(MIN_SPIRAL_STEPS, Math.ceil(SPIRAL_STEPS_PER_TURN * Math.abs(turns)));
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
