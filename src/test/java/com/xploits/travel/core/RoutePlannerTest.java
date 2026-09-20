package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerTest {
    private static final Waypoint ORIGIN = new Waypoint(0, 0);
    private static final double HIGHWAY_MAX = 300;
    private static final double TOLERANCE = 0.001;

    private static Route plan(Destination destination, FlightPattern pattern) {
        return RoutePlanner.plan(ORIGIN, destination, pattern, PatternParams.defaults(), HIGHWAY_MAX);
    }

    private static Waypoint last(Route route) {
        return route.waypoints().get(route.waypoints().size() - 1);
    }

    @Test
    void aStraightRouteIsJustTheDestination() {
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.RECTO);
        assertFalse(route.isRejected());
        assertEquals(1, route.waypoints().size());
        assertEquals(10_000, last(route).x(), TOLERANCE);
    }

    @Test
    void everyPatternEndsExactlyAtTheDestination() {
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = plan(Destination.coordinates(20_000, 5_000), pattern);
            assertFalse(route.isRejected(), pattern + " no debería rechazarse con coordenadas");
            assertEquals(20_000, last(route).x(), TOLERANCE, pattern + " no termina en el destino");
            assertEquals(5_000, last(route).z(), TOLERANCE, pattern + " no termina en el destino");
        }
    }

    @Test
    void zigzagAlternatesSidesOfTheHeading() {
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        List<Waypoint> points = route.waypoints();

        // El rumbo es +X, así que la desviación se ve en Z y debe alternar de signo.
        double previous = 0;
        int changes = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            double z = points.get(i).z();
            if (previous != 0 && Math.signum(z) != Math.signum(previous)) changes++;
            previous = z;
        }
        assertTrue(changes >= 2, "el zigzag debe cruzar el rumbo varias veces, cruces: " + changes);
    }

    @Test
    void zigzagRespectsItsAmplitude() {
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        double amplitude = PatternParams.defaults().amplitude();

        for (Waypoint point : route.waypoints()) {
            assertTrue(Math.abs(point.z()) <= amplitude + TOLERANCE,
                "ningún punto debe alejarse más que la amplitud: " + point.z());
        }
    }

    @Test
    void aPeriodLongerThanTheTripDoesNotProduceAnAbsurdRoute() {
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(500, 0), FlightPattern.ZIGZAG,
            PatternParams.defaults(), HIGHWAY_MAX);

        assertFalse(route.isRejected());
        assertFalse(route.waypoints().isEmpty());
        assertEquals(500, last(route).x(), TOLERANCE);
    }

    @Test
    void theSpiralClosesOnTheDestination() {
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL);
        List<Waypoint> points = route.waypoints();
        Waypoint destination = new Waypoint(30_000, 0);

        // El radio debe decrecer de forma monótona en el tramo final.
        double previous = Double.MAX_VALUE;
        int decreasing = 0;
        for (Waypoint point : points) {
            double radius = point.distanceTo(destination);
            if (radius < previous) decreasing++;
            previous = radius;
        }
        assertTrue(decreasing >= points.size() - 2, "el radio debe cerrarse sobre el destino");
        assertEquals(0, last(route).distanceTo(destination), TOLERANCE);
    }

    @Test
    void aSpiralBiggerThanTheTripIsCappedInsteadOfOvershooting() {
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(1_000, 0), FlightPattern.ESPIRAL,
            PatternParams.defaults(), HIGHWAY_MAX);

        assertFalse(route.isRejected());
        for (Waypoint point : route.waypoints()) {
            assertTrue(point.x() >= -TOLERANCE, "la espiral no debe retroceder detrás del origen: " + point.x());
        }
    }

    @Test
    void theSpiralsFirstWaypointIsExactlyWhereTheStraightLegEnds() {
        // El tramo recto va hasta destino - u*radio: ese punto ES el primero de la espiral (j=0,
        // radio=radio). No debe sobrar un segundo waypoint a 2*radio del destino: eso dejaría un
        // salto de una "radio" entera sin ningún propósito antes de empezar a girar de verdad.
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL);
        Waypoint destination = new Waypoint(30_000, 0);
        Waypoint first = route.waypoints().get(0);

        assertEquals(PatternParams.defaults().spiralRadius(), first.distanceTo(destination), TOLERANCE,
            "el primer waypoint de la espiral debe estar a una radio del destino, no a dos");
    }

    @Test
    void theSpiralsFirstWaypointIsExactlyDestinationMinusUTimesRadius() {
        // No basta con fijar la distancia (radio): medir el ángulo desde +u en vez de -u también
        // da un primer punto a una radio de distancia, pero PASADO el destino, no antes. Solo
        // comprobar las componentes descarta esa alternativa.
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL);
        Waypoint first = route.waypoints().get(0);
        double radius = PatternParams.defaults().spiralRadius();

        // u = (1,0): el destino está directamente en +X desde el origen.
        assertEquals(30_000 - radius, first.x(), TOLERANCE,
            "el ángulo se mide desde -u: el primer punto debe quedar ANTES del destino");
        assertEquals(0, first.z(), TOLERANCE);
    }

    @Test
    void theSpiralStepCountScalesWithTheConfiguredTurns() {
        // Con 36 pasos fijos y más vueltas, el paso angular crece y la espiral se convierte en un
        // polígono estrellado. Duplicar las vueltas debe traducirse en más pasos, no en el mismo
        // número con más grados cada uno.
        Route defaultTurns = plan(Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL);
        PatternParams manyTurns = new PatternParams(200, 2000, 5000, 800, 1500, 3.0, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL,
            manyTurns, HIGHWAY_MAX);

        assertTrue(route.waypoints().size() > defaultTurns.waypoints().size(),
            "el doble de vueltas debe traducirse en más pasos");
    }

    @Test
    void theDecoyAimsAwayFirstAndCorrectsLater() {
        Route route = plan(Destination.coordinates(20_000, 0), FlightPattern.SENUELO);
        Waypoint correction = route.waypoints().get(0);

        assertTrue(Math.abs(correction.z()) > 1_000,
            "el primer tramo debe apuntar claramente fuera del rumbo real, z=" + correction.z());
        assertEquals(20_000, last(route).x(), TOLERANCE);
    }

    @Test
    void theDecoysCorrectionPointIsExactlyComputed() {
        // No basta con "se aparta más de 1000": con 60° en vez de 30°, o fracción 0.4 en vez de
        // 0.6, ese umbral se sigue cumpliendo. Fija el punto exacto.
        Route route = plan(Destination.coordinates(20_000, 0), FlightPattern.SENUELO);
        Waypoint correction = route.waypoints().get(0);

        double angle = Math.toRadians(PatternParams.defaults().decoyAngleDegrees());
        double fraction = PatternParams.defaults().decoyFraction();
        // u = (1,0): rotar u por el ángulo del señuelo dentro del plano XZ.
        double expectedX = Math.cos(angle) * 20_000 * fraction;
        double expectedZ = Math.sin(angle) * 20_000 * fraction;

        assertEquals(expectedX, correction.x(), TOLERANCE);
        assertEquals(expectedZ, correction.z(), TOLERANCE);
    }

    @Test
    void aHighwayDestinationLandsOnItsAxis() {
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.RECTO);
        assertEquals(50_000, last(route).x(), TOLERANCE);
        assertEquals(0, last(route).z(), TOLERANCE);
    }

    @Test
    void theFourAxesPointWhereTheySay() {
        assertEquals(1_000, Destination.highway(Axis.X_PLUS, 1_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(-1_000, Destination.highway(Axis.X_MINUS, 1_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(1_000, Destination.highway(Axis.Z_PLUS, 1_000).resolve(ORIGIN).z(), TOLERANCE);
        assertEquals(-1_000, Destination.highway(Axis.Z_MINUS, 1_000).resolve(ORIGIN).z(), TOLERANCE);
    }

    @Test
    void onAHighwayNoWaypointLeavesTheAllowedCorridorForAnyPattern() {
        // Crítico: mi brief solo mandó acotar ZIGZAG y QUIEBRO, pero ningún patrón debe sacar del
        // corredor (spec §4.2). Sin acotar también el radio de ESPIRAL, con los valores de fábrica
        // y highwayMaxAmplitude=300, el paso 6 (ángulo 90°) se va 1250 bloques fuera del eje.
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), pattern);

            if (pattern == FlightPattern.SENUELO) {
                assertTrue(route.isRejected(), "el señuelo debe rechazarse en autopista, no acotarse");
                continue;
            }

            assertFalse(route.isRejected(), pattern + " no debería rechazarse en autopista");
            for (Waypoint point : route.waypoints()) {
                assertTrue(Math.abs(point.z()) <= HIGHWAY_MAX + TOLERANCE,
                    pattern + " se salió del corredor: " + point.z());
            }
        }
    }

    @Test
    void quiebroUsesLegLengthAndLateralOffsetNotAmplitudeAndPeriod() {
        // Sin este test, intercambiar los parámetros del quiebro (usar amplitude/period en vez de
        // lateralOffset/legLength) pasaba todos los tests igual. Distancia elegida (13 000) para que
        // el último tramo no quede tan cerca del destino como para omitirse (ver
        // aZigzagDoesNotWasteAFinalOutAndBackRightAtTheDestination).
        Route route = plan(Destination.coordinates(13_000, 0), FlightPattern.QUIEBRO);
        List<Waypoint> points = route.waypoints();

        double legLength = PatternParams.defaults().legLength(); // 5000
        double lateralOffset = PatternParams.defaults().lateralOffset(); // 800
        int expectedLegs = (int) Math.floor(13_000 / legLength); // 2

        assertEquals(expectedLegs + 1, points.size(), "quiebro debe usar legLength como paso, no period");
        for (int i = 0; i < points.size() - 1; i++) {
            assertEquals(lateralOffset, Math.abs(points.get(i).z()), TOLERANCE,
                "quiebro debe usar lateralOffset como amplitud, no amplitude");
        }
    }

    @Test
    void aZeroOrNegativePeriodFallsBackToTheStraightRouteInsteadOfHanging() {
        // (int) Math.floor(distancia / 0.0) es Integer.MAX_VALUE: sin este caso especial, dos mil
        // millones de iteraciones llenando una lista cuelgan el cliente o lo dejan sin memoria.
        PatternParams zeroPeriod = new PatternParams(200, 0, 5000, 800, 1500, 1.5, 30, 0.6);
        Route zigzagRoute = RoutePlanner.plan(ORIGIN, Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG,
            zeroPeriod, HIGHWAY_MAX);
        assertFalse(zigzagRoute.isRejected());
        assertEquals(1, zigzagRoute.waypoints().size());
        assertEquals(10_000, last(zigzagRoute).x(), TOLERANCE);

        PatternParams negativeLegLength = new PatternParams(200, 2000, -5, 800, 1500, 1.5, 30, 0.6);
        Route quiebroRoute = RoutePlanner.plan(ORIGIN, Destination.coordinates(10_000, 0), FlightPattern.QUIEBRO,
            negativeLegLength, HIGHWAY_MAX);
        assertFalse(quiebroRoute.isRejected());
        assertEquals(1, quiebroRoute.waypoints().size());
    }

    @Test
    void aTinyPeriodOverALongTripIsCappedInsteadOfExplodingTheWaypointCount() {
        PatternParams tinyPeriod = new PatternParams(200, 1, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(100_000, 0), FlightPattern.ZIGZAG,
            tinyPeriod, HIGHWAY_MAX);

        assertFalse(route.isRejected());
        assertTrue(route.waypoints().size() <= RoutePlanner.MAX_PATTERN_WAYPOINTS + 1,
            "el número de waypoints debe estar acotado: " + route.waypoints().size());
    }

    @Test
    void theWaypointCoordinatesMatchFloorOfDistanceOverPeriodForANonExactDivision() {
        // Fija las coordenadas exactas, no solo la cuenta: con floor(10500/2000)=5, el resto es 500,
        // que no dispara la omisión del último punto (500 >= amplitud 200), así que la omisión no
        // puede enmascarar el operador. Con ceil(10500/2000)=6 el resto sería 10500-12000=-1500 -un
        // resto negativo, que ya no dispara la omisión tras el fix de más abajo-, así que ceil deja
        // un séptimo waypoint que floor no tiene: el test cambia de tamaño Y de coordenadas.
        // Verificado a mano cambiando floor por ceil en el código: este test se pone en rojo (ver
        // "Ronda de arreglo" en el informe).
        Route route = plan(Destination.coordinates(10_500, 0), FlightPattern.ZIGZAG);
        List<Waypoint> points = route.waypoints();

        double[][] expected = {
            {2_000, 200}, {4_000, -200}, {6_000, 200}, {8_000, -200}, {10_000, 200}, {10_500, 0},
        };
        assertEquals(expected.length, points.size(), "número de waypoints");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], points.get(i).x(), TOLERANCE, "waypoint " + i + " (x)");
            assertEquals(expected[i][1], points.get(i).z(), TOLERANCE, "waypoint " + i + " (z)");
        }
    }

    @Test
    void aZigzagDoesNotWasteAFinalOutAndBackRightAtTheDestination() {
        // distancia=10000, periodo=2000: el paso 5 (el último) cae exactamente a la altura del
        // destino y desviaría los 200 bloques de amplitud sin ganar ningún avance. Sin la omisión
        // habría 6 puntos (5 de patrón + destino); con ella, 5.
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        assertEquals(5, route.waypoints().size(), "el último rodeo, pegado al destino, debe omitirse");
    }

    @Test
    void aShortQuiebroTripKeepsItsOnlyLateralPointInsteadOfDegradingToAStraightLine() {
        // legLength=5000, lateralOffset=800 (fábrica), viaje de 5200: el único punto de patrón
        // (i=1) cae con un resto de 200, dentro de la ventana de omisión (200 < 800). Omitirlo
        // dejaría una ruta completamente recta pese a haber pedido QUIEBRO -degradación silenciosa,
        // la misma que rechazamos con el señuelo en autopista. La ventana es del 16% del tramo, no
        // un residuo insignificante: el punto debe conservarse.
        Route route = plan(Destination.coordinates(5_200, 0), FlightPattern.QUIEBRO);
        List<Waypoint> points = route.waypoints();

        assertEquals(2, points.size(), "el único punto lateral debe conservarse, no degradar a línea recta");
        assertEquals(800, Math.abs(points.get(0).z()), TOLERANCE);
    }

    @Test
    void theDecoyIsRefusedOnAHighwayNotSilentlyDowngraded() {
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.SENUELO);

        assertTrue(route.isRejected(), "el señuelo sacaría del eje y debe rechazarse");
        assertTrue(route.rejection().toLowerCase().contains("autopista"),
            "el motivo debe explicarlo: " + route.rejection());
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");
    }

    @Test
    void aDestinationEqualToTheOriginDoesNotBlowUp() {
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(0, 0), pattern,
                PatternParams.defaults(), HIGHWAY_MAX);
            assertFalse(route.isRejected(), pattern + " no debería rechazar un destino nulo");
        }
    }

    @Test
    void aHighwayDestinationWithoutAnAxisIsRejectedEagerly() {
        // Antes: new Destination(true, 0, 0, null, 5).resolve(...) reventaba con un NullPointerException
        // confuso dentro del switch. Ahora falla al construirse, con un mensaje claro.
        assertThrows(IllegalArgumentException.class, () -> new Destination(true, 0, 0, null, 5));
    }

    @Test
    void anAcceptedRouteWithNoWaypointsIsRejectedAtConstruction() {
        // Route.of(List.of()) producía una ruta aceptada y vacía que nadie sabría interpretar.
        assertThrows(IllegalArgumentException.class, () -> Route.of(List.of()));
    }
}
