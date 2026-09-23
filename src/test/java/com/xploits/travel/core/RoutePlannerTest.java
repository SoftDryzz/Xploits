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
    /**
     * Un corredor lo bastante ancho para que la ESPIRAL quepa dentro con los valores de fábrica. Con
     * los 300 de HIGHWAY_MAX -que son los de fábrica del módulo- la espiral se rechaza por dejar los
     * pasos a 8 bloques unos de otros, y eso tiene su propio test: ver
     * aSpiralSqueezedIntoANarrowCorridorIsRefusedBecauseNoElytraCouldFlyIt.
     */
    private static final double HIGHWAY_WIDE = 500;
    private static final double MARGIN = RoutePlanner.DEFAULT_WAYPOINT_MARGIN;
    private static final double TOLERANCE = 0.001;

    private static Route plan(Destination destination, FlightPattern pattern) {
        return RoutePlanner.plan(ORIGIN, destination, pattern, PatternParams.defaults(), HIGHWAY_MAX, MARGIN);
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
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

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
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

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
                PatternParams.defaults(), 0, MARGIN);

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
            PatternParams.defaults(), 0, MARGIN);

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
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

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
            manyTurns, HIGHWAY_MAX, MARGIN);

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
            Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), pattern,
                PatternParams.defaults(), HIGHWAY_WIDE, MARGIN);

            if (pattern == FlightPattern.SENUELO) {
                assertTrue(route.isRejected(), "el señuelo debe rechazarse en autopista, no acotarse");
                continue;
            }

            assertFalse(route.isRejected(), pattern + " no debería rechazarse en autopista: " + route.rejection());
            for (Waypoint point : route.waypoints()) {
                assertTrue(Math.abs(point.z()) <= HIGHWAY_WIDE + TOLERANCE,
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
                    caso.params(), HIGHWAY_MAX, MARGIN),
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
            new PatternParams(200, 2000, 0, 800, 1500, 1.5, 30, 0.6), HIGHWAY_MAX, MARGIN);

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
            new PatternParams(200, 2000, 5000, 800, 1500, 0, 30, 0.6), HIGHWAY_MAX, MARGIN);

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
            zeroFraction, HIGHWAY_MAX, MARGIN);
        assertTrue(byFraction.isRejected(), "una fracción de 0 deja el señuelo en el origen");
        assertTrue(byFraction.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");
        assertTrue(byFraction.rejection().contains("fracción"),
            "el motivo debe nombrar el ajuste: " + byFraction.rejection());
        assertTrue(byFraction.rejection().contains("recta"),
            "el motivo debe decir la salida concreta: " + byFraction.rejection());

        for (double degrees : new double[] {0, 180}) {
            PatternParams flatAngle = new PatternParams(200, 2000, 5000, 800, 1500, 1.5, degrees, 0.6);
            Route byAngle = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.SENUELO,
                flatAngle, HIGHWAY_MAX, MARGIN);

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
            new PatternParams(200, 2000, 5000, 800, 1500, -1.5, 30, 0.6), HIGHWAY_MAX, MARGIN);
        assertFalse(spiral.isRejected(), "una espiral al revés sigue siendo una espiral");
        assertTrue(spiral.waypoints().stream().anyMatch(point -> Math.abs(point.z()) > 1),
            "la espiral al revés debe apartarse del eje de verdad");

        Route decoy = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.SENUELO,
            new PatternParams(200, 2000, 5000, 800, 1500, 1.5, 30, -0.6), HIGHWAY_MAX, MARGIN);
        assertFalse(decoy.isRejected(), "una fracción negativa apunta hacia atrás, pero apunta fuera del eje");
        assertTrue(Math.abs(decoy.waypoints().get(0).z()) > 1_000,
            "el punto de corrección debe quedar claramente fuera del rumbo real");
    }

    @Test
    void aPatternThatDoesNotFitUnderTheWaypointCapIsRefusedInsteadOfOndulatingOnlyTheFirstHalf() {
        // Antes este test consagraba el fallo: daba por buena la ruta y solo miraba que la CUENTA de
        // waypoints estuviera acotada, sin mirar la forma que salía. Y el tope acota la cuenta, no el
        // alcance del patrón: con el periodo mínimo del deslizador (100) y un destino a 100 000
        // bloques hacen falta 1000 cambios de lado, se truncaban a 500, y el zigzag ondulaba los
        // primeros 50 000 mientras los otros 50 000 salían en una línea recta perfecta apuntando a la
        // base -el tramo que llega a casa, la peor mitad donde dejar una recta. Sin rechazo y sin
        // aviso, mientras la spec §5 promete "el patrón se aplica en todo el trayecto".
        PatternParams shortPeriod = new PatternParams(200, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(100_000, 0), FlightPattern.ZIGZAG,
            shortPeriod, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "un patrón que solo cubriría media ruta debe rechazarse");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");

        // El motivo, con el mismo listón que los otros tres: el ajuste que el jugador puede tocar, su
        // valor actual, y el número concreto al que subirlo para desatascarse.
        String reason = route.rejection();
        assertTrue(reason.contains("periodo"), "el motivo debe nombrar el ajuste: " + reason);
        assertTrue(reason.contains("100 bloques"), "el motivo debe decir el periodo que tiene: " + reason);
        assertTrue(reason.contains("100000"), "el motivo debe decir la distancia del viaje: " + reason);
        assertTrue(reason.contains("200 bloques"), "el motivo debe decir a cuánto subirlo: " + reason);
        assertTrue(reason.contains("recta"), "el motivo debe decir qué saldría mal: " + reason);
        assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
    }

    @Test
    void aQuiebroThatOverflowsTheCapNamesItsTramoAndTheLegItWouldNeed() {
        // El motivo se lee en el chat: el QUIEBRO se configura con "tramo", no con "periodo", y el
        // número que le hace falta es el suyo -ceil(5 000 000/500) = 10 000-, no el del zigzag.
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(5_000_000, 0), FlightPattern.QUIEBRO,
            PatternParams.defaults(), HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "1000 tramos no caben bajo el tope");
        String reason = route.rejection();
        assertTrue(reason.contains("tramo"), "el motivo del quiebro debe hablar de su tramo: " + reason);
        assertFalse(reason.contains("periodo"), "el quiebro no se configura con periodo: " + reason);
        assertTrue(reason.contains("10000 bloques"), "el motivo debe decir a cuánto subir el tramo: " + reason);
    }

    @Test
    void aLateralPatternThatFitsUnderTheCapKeepsOndulatingRightUpToTheDestination() {
        // La otra cara del rechazo, y el test que mira la FORMA y no la cuenta: con el periodo justo
        // en el límite (500 cambios de lado exactos) la ruta se acepta, y entonces tiene que ondular
        // hasta el final. Si alguien vuelve a truncar la cuenta en vez de rechazar, el salto entre
        // dos waypoints consecutivos delata la recta que aparece al final.
        //
        // Periodo 250 y viaje de 125 000 -antes, 200 y 100 000- para que siga habiendo 500 cambios de
        // lado exactos y además los waypoints queden separados lo suficiente: con periodo 200 y
        // amplitud 200 el hueco es hypot(200, 200) = 283 bloques, por debajo de la separación mínima,
        // y esta ruta se rechazaría por otra cosa antes de llegar a lo que este test mira.
        PatternParams borderline = new PatternParams(200, 250, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(125_000, 0), FlightPattern.ZIGZAG,
            borderline, HIGHWAY_MAX, MARGIN);

        assertFalse(route.isRejected(), "500 cambios de lado justos sí caben: " + route.rejection());
        List<Waypoint> points = route.waypoints();
        assertTrue(points.size() <= RoutePlanner.MAX_PATTERN_WAYPOINTS + 1,
            "el tope sigue vigente, el rechazo no lo deroga: " + points.size());

        // Ningún tramo recto puede pasar de dos periodos: uno por el avance normal y otro por el
        // último punto omitido cuando cae pegado al destino. Media ruta recta no pasa de ahí.
        double previousX = ORIGIN.x();
        for (Waypoint point : points) {
            assertTrue(point.x() - previousX <= 2 * borderline.period() + TOLERANCE,
                "hueco recto entre waypoints: de " + previousX + " a " + point.x());
            previousX = point.x();
        }

        // Y el desvío sigue vivo en el último tercio, no solo al principio del viaje.
        List<Waypoint> lastThird = points.subList(points.size() * 2 / 3, points.size() - 1);
        for (Waypoint point : lastThird) {
            assertEquals(borderline.amplitude(), Math.abs(point.z()), TOLERANCE,
                "el último tercio debe seguir desviándose la amplitud entera: " + point.z());
        }
    }

    @Test
    void theWaypointCoordinatesMatchFloorOfDistanceOverPeriodForANonExactDivision() {
        // Fija las coordenadas exactas, no solo la cuenta. La distancia está elegida para que floor
        // y ceil den listas distintas: con d=10500 el resto de floor era 500, un hueco final de
        // hypot(500, 200) = 538 que no se omite, y las dos ramas acababan dando lo mismo.
        //
        // Con d=10100 difieren:
        //   floor(10100/2000)=5 -> resto 100 -> hueco final hypot(100, 200) = 224, por debajo de la
        //       separación mínima -> se omite el 5º punto: 4 de patrón + destino = 5.
        //   ceil(10100/2000)=6  -> resto 10100-12000 = -1500 -> hueco final hypot(1500, 200) = 1513,
        //       muy por encima de la separación -> no se omite nada: 6 de patrón + destino = 7.
        // La de ceil no coincide con las 5 de floor ni en tamaño ni en coordenadas. El hueco se mide
        // con hypot y no con el resto pelado justo por esto: un resto negativo no es un hueco corto,
        // y hypot se lleva por delante el signo sin necesidad de una guarda aparte.
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
        // habría 6 puntos (5 de patrón + destino); con ella, 5. Y desde el arreglo del aterrizaje
        // hay un segundo motivo para omitirlo: esos 200 bloques hasta el destino son menos de la
        // separación mínima, así que Baritone aterrizaría ahí y otra vez en el destino.
        Route route = plan(Destination.coordinates(10_000, 0), FlightPattern.ZIGZAG);
        assertEquals(5, route.waypoints().size(), "el último rodeo, pegado al destino, debe omitirse");
    }

    @Test
    void aShortQuiebroTripKeepsItsOnlyLateralPointInsteadOfDegradingToAStraightLine() {
        // legLength=5000, lateralOffset=800 (fábrica), viaje de 5200: el único punto de patrón
        // (i=1) cae con un resto de 200 sobre el eje, pero a hypot(200, 800) = 824 bloques del
        // destino, por encima de la separación mínima. Así que se conserva: Baritone puede volar de
        // él al destino sin aterrizar por el camino, y omitirlo dejaría una ruta completamente recta
        // pese a haber pedido QUIEBRO -degradación silenciosa, la misma que rechazamos con el señuelo
        // en autopista.
        //
        // El criterio de omisión es el hueco real hasta el destino, no el resto sobre el eje: con el
        // resto pelado (200 < 800) este punto se omitiría y la ruta saldría recta, que es como estaba
        // antes de medir los huecos de verdad.
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
        // ondulación. La omisión no actúa porque el hueco hasta el destino son los 800 del desvío,
        // por encima de la separación mínima: el rodeo se vuela entero y sin aterrizar en medio, y
        // más vale eso que un viaje recto que el jugador cree ondulado.
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
    void theMarginClearsBaritonesLandingDistanceWithRoomToSpare() {
        // El número del que cuelga todo el arreglo: Baritone empieza a aterrizar a 48 bloques de su
        // objetivo (leído de su bytecode: la constante 2304.0 = 48² justo antes de "Path complete,
        // searching for safe landing spot..."), así que cualquier margen por debajo de eso llega
        // tarde por definición. El margen de fábrica de antes eran 30: aterrizaba en cada waypoint.
        assertEquals(48, RoutePlanner.BARITONE_LANDING_DISTANCE, TOLERANCE);
        assertTrue(RoutePlanner.MIN_WAYPOINT_MARGIN > RoutePlanner.BARITONE_LANDING_DISTANCE,
            "hasta el margen más bajo que se admite tiene que ganarle a los 48 de Baritone");
        assertTrue(RoutePlanner.DEFAULT_WAYPOINT_MARGIN >= 2 * RoutePlanner.BARITONE_LANDING_DISTANCE,
            "el de fábrica, con holgura para el recálculo de ruta de Baritone");

        // Y la separación mínima es el doble del margen, nunca menos que el suelo físico: el margen
        // se gasta dos veces -al soltar el waypoint N ya estás a un margen de él- y sin el doble los
        // waypoints se consumen de dos en dos.
        assertEquals(2 * RoutePlanner.DEFAULT_WAYPOINT_MARGIN,
            RoutePlanner.minimumSpacing(RoutePlanner.DEFAULT_WAYPOINT_MARGIN), TOLERANCE);
        assertEquals(1_000, RoutePlanner.minimumSpacing(500), TOLERANCE);
        assertEquals(RoutePlanner.MIN_WAYPOINT_SPACING, RoutePlanner.minimumSpacing(10), TOLERANCE,
            "por debajo del suelo físico no se baja aunque el margen sea ridículo");
    }

    @Test
    void onlyTheLastWaypointGetsTheArrivalMarginBecauseItIsTheOnlyOneBaritoneShouldLandOn() {
        // La otra mitad del arreglo, y pura aritmética, así que vive aquí y no en el adaptador: los
        // waypoints intermedios se sueltan waypoint-margin bloques antes para que Baritone no llegue
        // a aterrizar en ellos, y el último NO -ahí aterrizar es lo que se ha pedido, y adelantarse
        // 150 bloques sería mandarle "cancel" en mitad del descenso y soltar al jugador en el aire-.
        for (int index = 0; index < 4; index++) {
            assertEquals(MARGIN, RoutePlanner.reachedMargin(index, 5, MARGIN), TOLERANCE,
                "el waypoint intermedio " + index + " debe soltarse con el margen entero");
        }
        assertEquals(RoutePlanner.ARRIVAL_MARGIN, RoutePlanner.reachedMargin(4, 5, MARGIN), TOLERANCE,
            "el último waypoint es el destino: ahí no se adelanta nada");

        // Y una ruta de un solo waypoint -RECTO- es su propio destino: margen de llegada, no de paso.
        assertEquals(RoutePlanner.ARRIVAL_MARGIN, RoutePlanner.reachedMargin(0, 1, MARGIN), TOLERANCE);
    }

    @Test
    void noAcceptedRouteLeavesTwoWaypointsCloserThanBaritoneCanFly() {
        // La invariante que hace que el margen sirva de algo, medida sobre la ruta entera y desde el
        // propio origen: si dos waypoints seguidos están a menos del doble del margen, el tick que
        // suelta el primero suelta también el segundo y el patrón se consume en ráfaga sin volarse.
        // Antes de este arreglo la espiral de fábrica terminaba con pasos de 42 bloques.
        // El doble del margen escrito a mano y no minimumSpacing(MARGIN): si este test le preguntara
        // a la misma función que se está comprobando, moverla no lo pondría en rojo.
        double spacing = 2 * MARGIN;
        for (FlightPattern pattern : FlightPattern.values()) {
            for (double distance : new double[] {5_000, 10_100, 30_000, 120_000}) {
                Route route = plan(Destination.coordinates(distance, 0), pattern);
                if (route.isRejected()) continue;

                Waypoint previous = ORIGIN;
                for (Waypoint point : route.waypoints()) {
                    assertTrue(previous.distanceTo(point) >= spacing - TOLERANCE,
                        pattern + " a " + distance + " deja un hueco de " + previous.distanceTo(point)
                            + " bloques, y hacen falta " + spacing);
                    previous = point;
                }
            }
        }
    }

    @Test
    void theSpiralKeepsItsTurnsButDropsTheUnflyableCoreInsteadOfLandingOnIt() {
        // La espiral termina por definición en radio cero, así que sus últimos pasos están a unos
        // pocos bloques unos de otros: con los valores de fábrica, los últimos son de 42 bloques,
        // menos que los 48 a los que Baritone ya está aterrizando. Ningún ajuste lo arregla -es la
        // forma de la curva-, así que esos pasos no se emiten.
        //
        // Lo que NO puede pasar es que el recorte se lleve la espiral por delante: tiene que seguir
        // dando vueltas alrededor del destino, cruzando el eje a los dos lados.
        Route route = plan(Destination.coordinates(30_000, 0), FlightPattern.ESPIRAL);
        List<Waypoint> points = route.waypoints();
        assertFalse(route.isRejected());

        assertTrue(points.size() < 37, "los pasos del núcleo, involables, no deben emitirse: " + points.size());
        assertTrue(points.stream().anyMatch(p -> p.z() > 100), "la espiral debe pasar por un lado del eje");
        assertTrue(points.stream().anyMatch(p -> p.z() < -100), "y por el otro");
        assertEquals(0, last(route).distanceTo(new Waypoint(30_000, 0)), TOLERANCE,
            "y terminar en el destino exacto, que es donde Baritone sí debe aterrizar");
    }

    @Test
    void aTightZigzagIsRefusedInsteadOfHavingItsPeriodStretchedBehindThePlayersBack() {
        // Un zigzag de periodo 100 y amplitud 50 deja los waypoints a hypot(100, 50) = 111 bloques:
        // Baritone aterrizaría en todos. La salida tentadora -quedarse con uno de cada tres, que sí
        // daría huecos de 300- es exactamente la sustitución silenciosa que esta clase no entrega:
        // sería un zigzag de periodo 300 cuando el jugador puso 100. Se rechaza.
        //
        // El viaje es de 20 000 para que quepa bajo el tope de waypoints (200 cambios de lado) y el
        // rechazo que salte sea este y no el del tope.
        PatternParams tight = new PatternParams(50, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            tight, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "un zigzag que no se puede volar debe rechazarse, no estirarse");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");

        String reason = route.rejection();
        assertTrue(reason.contains("periodo"), "el motivo debe nombrar el ajuste: " + reason);
        assertTrue(reason.contains("100 bloques"), "el motivo debe decir el periodo que tiene: " + reason);
        assertTrue(reason.contains("111"), "el motivo debe decir el hueco que deja: " + reason);
        assertTrue(reason.contains("300"), "el motivo debe decir la separación que hace falta: " + reason);
        assertTrue(reason.contains("296"), "el motivo debe decir a cuánto subir el periodo: " + reason);
        assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);
    }

    @Test
    void theMotiveOfATightQuiebroNamesItsTramoAndItsDesvioNotTheZigzagOnes() {
        // El motivo se lee en el chat y tiene que apuntar a los ajustes que el jugador puede tocar:
        // el QUIEBRO se configura con tramo y desvío lateral, no con periodo y amplitud.
        PatternParams tight = new PatternParams(200, 2000, 100, 50, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.QUIEBRO,
            tight, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected());
        String reason = route.rejection();
        assertTrue(reason.contains("tramo"), "el motivo del quiebro debe hablar de su tramo: " + reason);
        assertTrue(reason.contains("desvío lateral"), "y de su desvío lateral: " + reason);
        assertFalse(reason.contains("periodo"), "el quiebro no se configura con periodo: " + reason);
    }

    @Test
    void aTightLateralPatternOnAHighwaySaysThatWideningItsSideAloneWouldNotHelp() {
        // Con el corredor acotando el desvío, "sube la amplitud a 283" manda al jugador a mover un
        // deslizador que no cambia nada: el corredor la seguirá acotando a 50. El motivo tiene que
        // decir que hay que subir también el ancho del corredor, o es un rechazo que no desatasca.
        PatternParams tight = new PatternParams(200, 100, 5000, 800, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 20_000), FlightPattern.ZIGZAG,
            tight, 50, MARGIN);

        assertTrue(route.isRejected());
        String reason = route.rejection();
        assertTrue(reason.toLowerCase().contains("corredor"),
            "el motivo debe decir que el corredor acota el desvío: " + reason);
        assertTrue(reason.contains("50"), "y a cuánto lo acota: " + reason);
    }

    @Test
    void aLateralPatternWhoseOnlyDetourWouldSitOnTopOfTheDestinationIsRefused() {
        // tramo 5000 y desvío 100 en un viaje de 5000: el único punto de patrón cae a la altura
        // exacta del destino, a 100 bloques de él. Conservarlo hace aterrizar a Baritone ahí y otra
        // vez en el destino; omitirlo deja la ruta recta pese a haberse pedido QUIEBRO. Ninguna de
        // las dos vale, así que se rechaza con el número al que subir el desvío.
        PatternParams narrow = new PatternParams(200, 2000, 5000, 100, 1500, 1.5, 30, 0.6);
        Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(5_000, 0), FlightPattern.QUIEBRO,
            narrow, HIGHWAY_MAX, MARGIN);

        assertTrue(route.isRejected(), "un desvío que Baritone convertiría en aterrizaje debe rechazarse");
        String reason = route.rejection();
        assertTrue(reason.contains("desvío lateral"), "el motivo debe nombrar el ajuste: " + reason);
        assertTrue(reason.contains("100 bloques"), "el motivo debe decir el hueco que deja: " + reason);
        assertTrue(reason.contains("300"), "el motivo debe decir a cuánto subirlo: " + reason);
        assertTrue(reason.contains("recta"), "el motivo debe decir qué saldría mal: " + reason);
    }

    @Test
    void aSpiralSqueezedIntoANarrowCorridorIsRefusedBecauseNoElytraCouldFlyIt() {
        // Con el ancho de corredor de fábrica (300) la espiral queda acotada a radio 300, y una
        // espiral de radio 300 con vuelta y media deja pasos de 8 bloques. Quitando los involables
        // solo sobrevive el primero, que está exactamente sobre el eje: la ruta sería la recta con un
        // waypoint decorativo. Antes de este arreglo se entregaba, y Baritone aterrizaba en casi cada
        // paso; ahora se rechaza diciendo a cuánto subir el corredor.
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.ESPIRAL);

        assertTrue(route.isRejected(), "una espiral de radio 300 no la vuela ninguna elytra");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");

        String reason = route.rejection();
        assertTrue(reason.contains("pasos de 8 bloques"), "el motivo debe decir el paso que deja: " + reason);
        assertTrue(reason.toLowerCase().contains("corredor"),
            "el motivo debe señalar quién acota el radio: " + reason);
        assertTrue(reason.contains("338"), "el motivo debe decir a cuánto subir ese ancho: " + reason);

        // Y con el corredor por encima de ese número vuelve a volarse: el rechazo es accionable de
        // verdad, no un "no se puede" con un número inventado.
        Route wider = RoutePlanner.plan(ORIGIN, Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.ESPIRAL,
            PatternParams.defaults(), 338, MARGIN);
        assertFalse(wider.isRejected(), "con el ancho que dice el motivo debe volar: " + wider.rejection());
    }

    @Test
    void aDecoyOnATooShortTripIsRefusedBecauseItsCorrectionPointWouldBeALandingSpot() {
        // Los dos tramos del señuelo son proporcionales a la distancia del viaje, así que en un viaje
        // corto los dos se quedan cortos a la vez: con los 30° y el 60% de fábrica, un viaje de 500
        // bloques deja el punto de corrección a 283 del destino. Baritone aterrizaría ahí en vez de
        // pasar por él, que es justo lo contrario de un señuelo.
        Route route = plan(Destination.coordinates(500, 0), FlightPattern.SENUELO);

        assertTrue(route.isRejected(), "un señuelo de 500 bloques no se puede volar");
        assertTrue(route.waypoints().isEmpty(), "una ruta rechazada no lleva puntos");

        String reason = route.rejection();
        assertTrue(reason.contains("283"), "el motivo debe decir el tramo que deja: " + reason);
        assertTrue(reason.contains("500"), "el motivo debe decir la distancia del viaje: " + reason);
        assertTrue(reason.contains("530"), "el motivo debe decir a cuánto alejar el destino: " + reason);
        assertTrue(reason.contains("RECTO"), "el motivo debe ofrecer una salida: " + reason);

        // Y a la distancia que dice el motivo vuelve a volarse.
        assertFalse(plan(Destination.coordinates(530, 0), FlightPattern.SENUELO).isRejected(),
            "el número del motivo tiene que ser el bueno");
    }

    @Test
    void aBiggerMarginDemandsMoreRoomAndRefusesPatternsThatFlewWithTheDefaultOne() {
        // El margen es un ajuste, así que la separación que exige la geometría se mueve con él: quien
        // lo suba a 500 pide huecos de 1000 bloques y algunos patrones dejarán de caber. Eso se dice,
        // con el ajuste nombrado, en vez de volar un patrón recortado en silencio.
        PatternParams modest = new PatternParams(200, 400, 5000, 800, 1500, 1.5, 30, 0.6);
        assertFalse(RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            modest, HIGHWAY_MAX, MARGIN).isRejected(), "con el margen de fábrica cabe");

        Route tight = RoutePlanner.plan(ORIGIN, Destination.coordinates(20_000, 0), FlightPattern.ZIGZAG,
            modest, HIGHWAY_MAX, 500);
        assertTrue(tight.isRejected(), "con el margen a 500 ya no cabe");
        assertTrue(tight.rejection().contains("waypoint-margin"),
            "el motivo debe nombrar el ajuste que lo ha estrechado: " + tight.rejection());
        assertTrue(tight.rejection().contains("500"), "y el valor que tiene: " + tight.rejection());
    }

    @Test
    void aDestinationEqualToTheOriginDoesNotBlowUp() {
        for (FlightPattern pattern : FlightPattern.values()) {
            Route route = RoutePlanner.plan(ORIGIN, Destination.coordinates(0, 0), pattern,
                PatternParams.defaults(), HIGHWAY_MAX, MARGIN);
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

    @Test
    void loQueQuedaPorVolarDesdeDondeEstasHastaElFinal() {
        List<Waypoint> ruta = List.of(new Waypoint(0, 0), new Waypoint(300, 400), new Waypoint(300, 1000));
        assertEquals(1100.0, RoutePlanner.bloquesRestantes(ruta, 0, new Waypoint(0, 0)), 1e-9);
        assertEquals(1100.0, RoutePlanner.bloquesRestantes(ruta, 1, new Waypoint(0, 0)), 1e-9);
        assertEquals(600.0, RoutePlanner.bloquesRestantes(ruta, 2, new Waypoint(300, 400)), 1e-9);
        assertThrows(IndexOutOfBoundsException.class, () -> RoutePlanner.bloquesRestantes(ruta, 3, new Waypoint(0, 0)));
        assertThrows(IndexOutOfBoundsException.class, () -> RoutePlanner.bloquesRestantes(ruta, -1, new Waypoint(0, 0)));
        assertThrows(NullPointerException.class, () -> RoutePlanner.bloquesRestantes(ruta, 0, null));
    }
}
