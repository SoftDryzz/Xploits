package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theDecoyAimsAwayFirstAndCorrectsLater() {
        Route route = plan(Destination.coordinates(20_000, 0), FlightPattern.SENUELO);
        Waypoint correction = route.waypoints().get(0);

        assertTrue(Math.abs(correction.z()) > 1_000,
            "el primer tramo debe apuntar claramente fuera del rumbo real, z=" + correction.z());
        assertEquals(20_000, last(route).x(), TOLERANCE);
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
    void onAHighwayNoWaypointLeavesTheAllowedCorridor() {
        Route route = plan(Destination.highway(Axis.X_PLUS, 50_000), FlightPattern.ZIGZAG);

        for (Waypoint point : route.waypoints()) {
            assertTrue(Math.abs(point.z()) <= HIGHWAY_MAX + TOLERANCE,
                "se salió del corredor: " + point.z());
        }
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
}
