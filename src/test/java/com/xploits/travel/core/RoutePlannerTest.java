package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    void aTripShorterThanASingleLegIsRefusedInsteadOfSilentlyFlyingStraight() {
        // Antes este test consagraba el fallo: daba por buena una ruta recta y no rechazada.
        // floor(500/2000)=0, así que no se genera NI UN punto de patrón y la ruta sale recta pese a
        // haber pedido ZIGZAG. Con el tramo de fábrica del QUIEBRO (5000) eso es todo viaje de menos
        // de 5000 bloques: el jugador pide evasión, vuela recto y se cree ondulando.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(500, 0), FlightPattern.ZIGZAG,
            PatternParams.defaults(), HIGHWAY_MAX);

        assertTrue(route.isRejected(), "un viaje más corto que un tramo debe rechazarse, no salir recto");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");

        // El motivo tiene que nombrar los dos números y la salida: un rechazo que no dice cómo
        // desatascarse es casi tan malo como el silencio.
        String reason = route.rejection();
        assertTrue(reason.contains("2000"), "el motivo debe decir el periodo configurado: " + reason);
        assertTrue(reason.contains("500"), "el motivo debe decir la distancia del viaje: " + reason);
        assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
    }

    @Test
    void aShortTripWithQuiebroNamesItsOwnSettingNotTheZigzagOne() {
        // El motivo se lee en el chat y tiene que apuntar al ajuste que el jugador puede tocar: el
        // QUIEBRO se configura con "tramo", no con "periodo".
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(4_000, 0), FlightPattern.QUIEBRO,
            PatternParams.defaults(), HIGHWAY_MAX);

        assertTrue(route.isRejected());
        String reason = route.rejection();
        assertTrue(reason.contains("tramo"), "el motivo del quiebro debe hablar de su tramo: " + reason);
        assertTrue(reason.contains("5000"), "el motivo debe decir el tramo configurado: " + reason);
        assertTrue(reason.contains("4000"), "el motivo debe decir la distancia del viaje: " + reason);
    }

    @Test
    void aLateralPatternWithItsAmplitudeCappedToZeroIsRefusedNotDrawnFlat() {
        // Se llega aquí sin ningún parámetro absurdo: un destino de autopista con el ancho del
        // corredor a 0 acota la amplitud efectiva a 0. Los puntos se generan -así que la guarda de
        // "points.size() > 1" no protege de esto- pero caen todos sobre el eje: una recta con
        // waypoints decorativos.
        for (FlightPattern pattern : List.of(FlightPattern.ZIGZAG, FlightPattern.QUIEBRO)) {
            Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), pattern,
                PatternParams.defaults(), 0);

            assertTrue(route.isRejected(), pattern + " con amplitud efectiva 0 debe rechazarse");
            assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");
            String reason = route.rejection();
            assertTrue(reason.contains("0"), "el motivo debe decir la amplitud efectiva: " + reason);
            assertTrue(reason.toLowerCase().contains("corredor"),
                "el motivo debe señalar de dónde viene el cero: " + reason);
            assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
        }
    }

    @Test
    void aSpiralWithItsRadiusCappedToZeroIsRefusedLikeAFlatZigzag() {
        // Mismo agujero que el lateral y, por coherencia con la doctrina, mismo trato: con radio 0
        // los 37 pasos caen exactamente sobre el destino. No es una espiral pequeña, es una recta
        // con waypoints repetidos.
        Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.ESPIRAL,
            PatternParams.defaults(), 0);

        assertTrue(route.isRejected(), "una espiral de radio 0 es una recta y debe rechazarse");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");
        String reason = route.rejection();
        assertTrue(reason.contains("1500"), "el motivo debe decir el radio configurado: " + reason);
        assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
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
    void aZeroOrNegativeStepIsRefusedInsteadOfSilentlyFlyingStraight() {
        // Antes este test consagraba el fallo: con el paso a 0 la ruta salía recta y NO rechazada,
        // la misma degradación silenciosa que las otras puertas ya rechazan. El jugador pone el
        // periodo a 0, vuela recto y se cree ondulando. Un paso de cero no funciona acotado: no
        // funciona, así que se rechaza con motivo.
        //
        // Lo que sí valía de la versión anterior se conserva: el motivo de aquella rama recta era no
        // colgar el cliente -(int) Math.floor(d/0.0) es Integer.MAX_VALUE-, y un rechazo tiene que
        // evitar el cuelgue igual de bien. El plazo lo comprueba: el rechazo sale sin entrar en
        // ningún bucle, muchísimo antes de dos segundos.
        record Caso(FlightPattern pattern, PatternParams params, String ajuste, String valor) {}
        List<Caso> casos = List.of(
            new Caso(FlightPattern.ZIGZAG, new PatternParams(200, 0, 5000, 800, 1500, 1.5, 30, 0.6),
                "periodo", "0"),
            new Caso(FlightPattern.ZIGZAG, new PatternParams(200, -2000, 5000, 800, 1500, 1.5, 30, 0.6),
                "periodo", "-2000"),
            new Caso(FlightPattern.QUIEBRO, new PatternParams(200, 2000, 0, 800, 1500, 1.5, 30, 0.6),
                "tramo", "0"),
            new Caso(FlightPattern.QUIEBRO, new PatternParams(200, 2000, -5, 800, 1500, 1.5, 30, 0.6),
                "tramo", "-5"));

        for (Caso caso : casos) {
            String quien = caso.pattern() + " con " + caso.ajuste() + " a " + caso.valor();
            Route route = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> RoutePlanner.plan(ORIGIN, Destination.coordinates(10_000, 0), caso.pattern(),
                    caso.params(), HIGHWAY_MAX),
                quien + " debe rechazarse de inmediato, sin entrar en ningún bucle");

            assertTrue(route.isRejected(), quien + " debe rechazarse, no salir recto en silencio");
            assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");

            String reason = route.rejection();
            assertTrue(reason.contains(caso.ajuste()),
                "el motivo debe nombrar el ajuste propio del patrón: " + reason);
            assertTrue(reason.contains(caso.valor()), "el motivo debe decir el valor que tiene: " + reason);
            assertTrue(reason.contains("recta"), "el motivo debe decir la salida concreta: " + reason);
            assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
        }
    }

    @Test
    void aQuiebroWithItsStepAtZeroNamesItsTramoNotTheZigzagPeriodo() {
        // El motivo se lee en el chat: tiene que apuntar al ajuste que el jugador puede tocar. Sin
        // esto, un motivo genérico ("el paso") pasaría el test de arriba por la vía del QUIEBRO.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(10_000, 0), FlightPattern.QUIEBRO,
            new PatternParams(200, 2000, 0, 800, 1500, 1.5, 30, 0.6), HIGHWAY_MAX);

        assertTrue(route.isRejected());
        assertFalse(route.rejection().contains("periodo"),
            "el quiebro no se configura con periodo: " + route.rejection());
    }

    @Test
    void aSpiralWithZeroTurnsIsRefusedBecauseItNeverLeavesTheAxis() {
        // Mismo agujero que el paso a cero, por el otro ajuste de la espiral: el ángulo es
        // 2*PI*0*fraction, o sea 0 en todos los pasos, y los 9 puntos se reparten sobre el propio
        // eje entre el destino y el punto a una radio antes. Es la aproximación recta de RECTO con
        // waypoints decorativos encima.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL,
            new PatternParams(200, 2000, 5000, 800, 1500, 0, 30, 0.6), HIGHWAY_MAX);

        assertTrue(route.isRejected(), "una espiral que no gira es una recta y debe rechazarse");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");
        String reason = route.rejection();
        assertTrue(reason.contains("vueltas"), "el motivo debe nombrar el ajuste de la espiral: " + reason);
        assertTrue(reason.contains("0"), "el motivo debe decir el valor que tiene: " + reason);
        assertTrue(reason.contains("recta"), "el motivo debe decir la salida concreta: " + reason);
        assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
    }

    @Test
    void aDecoyWhoseCorrectionPointLandsOnTheAxisIsRefusedInsteadOfFlyingStraight() {
        // El señuelo tiene el mismo agujero por sus dos ajustes: con la fracción a 0 el punto de
        // corrección ES el origen, y con el ángulo a 0 (o a 180) cae sobre la propia recta
        // origen-destino. En los dos casos el "señuelo" no despista a nadie y la ruta es la recta.
        PatternParams zeroFraction = new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, 0);
        Route byFraction = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.SENUELO,
            zeroFraction, HIGHWAY_MAX);
        assertTrue(byFraction.isRejected(), "una fracción de 0 deja el señuelo en el origen");
        assertTrue(byFraction.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");
        assertTrue(byFraction.rejection().contains("fracción"),
            "el motivo debe nombrar el ajuste: " + byFraction.rejection());
        assertTrue(byFraction.rejection().contains("recta"),
            "el motivo debe decir la salida concreta: " + byFraction.rejection());

        for (double degrees : new double[] {0, 180}) {
            PatternParams flatAngle = new PatternParams(200, 2000, 5000, 800, 1500, 1.5, degrees, 0.6);
            Route byAngle = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.SENUELO,
                flatAngle, HIGHWAY_MAX);

            assertTrue(byAngle.isRejected(), "un ángulo de " + degrees + " grados no aparta del eje");
            String reason = byAngle.rejection();
            assertTrue(reason.contains("ángulo"), "el motivo debe nombrar el ajuste: " + reason);
            assertTrue(reason.contains(String.valueOf((long) degrees)),
                "el motivo debe decir el valor que tiene: " + reason);
            assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
        }
    }

    @Test
    void negativeTurnsAndANegativeDecoyFractionStillDrawARealDetourSoTheyAreNotRefused() {
        // El rechazo es para lo que sale recto, no para todo número raro: con vueltas negativas la
        // espiral gira al otro lado -gira, que es lo único que se le pide- y con fracción negativa
        // el punto de corrección queda fuera del eje, detrás del origen. Caro, pero despista. Que
        // nadie convierta estos rechazos en un "<= 0" de brocha gorda.
        Route spiral = RoutePlanner.plan(ORIGIN, Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL,
            new PatternParams(200, 2000, 5000, 800, 1500, -1.5, 30, 0.6), HIGHWAY_MAX);
        assertFalse(spiral.isRejected(), "una espiral al revés sigue siendo una espiral");
        assertTrue(spiral.waypoints().stream().anyMatch(point -> Math.abs(point.z()) > 1),
            "la espiral al revés debe apartarse del eje de verdad");

        Route decoy = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.SENUELO,
            new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, -0.6), HIGHWAY_MAX);
        assertFalse(decoy.isRejected(), "una fracción negativa apunta hacia atrás, pero apunta fuera del eje");
        assertTrue(Math.abs(decoy.waypoints().get(0).z()) > 1_000,
            "el punto de corrección debe quedar claramente fuera del rumbo real");
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
        // Fija las coordenadas exactas, no solo la cuenta. La distancia está elegida para que este
        // test no dependa de la guarda hermana (el "remaining >= 0" de la omisión): con d=10500 el
        // resto de floor era 500 >= amplitud 200, así que ceil + quitar la guarda daba exactamente
        // la misma lista que floor y el test se quedaba verde por caridad del vecino.
        //
        // Con d=10100 las dos ramas difieren haya guarda o no:
        //   floor(10100/2000)=5 -> resto 100 < amplitud 200 -> se omite el 5º punto: 4 + destino.
        //   ceil(10100/2000)=6  -> resto 10100-12000 = -1500, negativo:
        //       con guarda -> no se omite nada: 6 puntos de patrón + destino = 7.
        //       sin guarda -> se omite el 6º: 5 puntos de patrón + destino = 6.
        // Ninguna de las dos coincide con las 5 de floor, ni en tamaño ni en coordenadas.
        Route route = plan(Destination.coordinates(10_100, 0), FlightPattern.ZIGZAG);
        List<Waypoint> points = route.waypoints();

        double[][] expected = {
            {2_000, 200}, {4_000, -200}, {6_000, 200}, {8_000, -200}, {10_100, 0},
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
    void aDestinationAtAnExactMultipleOfTheLegPaysTheFullOutAndBackOnPurpose() {
        // El peaje consciente de la decisión anterior, clavado para que nadie lo "arregle" sin
        // saberlo: legLength=5000 y d=5000 dan un solo punto de patrón (i=1) que cae a la altura
        // EXACTA del destino, resto 0. Son 800 bloques de ida y 800 de vuelta sin ningún avance ni
        // ondulación. La omisión no actúa porque dejaría la ruta sin un solo punto de patrón, y
        // más vale un rodeo entero que un viaje recto que el jugador cree ondulado.
        Route route = plan(Destination.coordinates(5_000, 0), FlightPattern.QUIEBRO);
        List<Waypoint> points = route.waypoints();

        assertFalse(route.isRejected(), "el tramo cabe justo una vez: hay patrón, no hay nada que rechazar");
        assertEquals(2, points.size());
        assertEquals(5_000, points.get(0).x(), TOLERANCE, "el desvío cae a la altura exacta del destino");
        assertEquals(800, points.get(0).z(), TOLERANCE);
        assertEquals(5_000, points.get(1).x(), TOLERANCE);
        assertEquals(0, points.get(1).z(), TOLERANCE);
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
