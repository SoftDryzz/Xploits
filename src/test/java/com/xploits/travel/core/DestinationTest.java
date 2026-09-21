package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolver un destino es aritmética pura -un punto de partida y unos números- y por eso se prueba
 * sin arrancar el juego. Aquí viven las dos decisiones del jugador que el núcleo tiene que cumplir
 * al pie de la letra: que en una diagonal la distancia son bloques recorridos, y que un
 * desplazamiento es un desplazamiento y no unas coordenadas.
 */
class DestinationTest {
    private static final double TOLERANCE = 0.001;

    /**
     * Un punto de partida que no es el origen del mundo, a propósito: con (0, 0) un desplazamiento y
     * unas coordenadas absolutas dan el mismo punto, y los tests darían por buenas las dos cosas.
     */
    private static final Waypoint ORIGIN = new Waypoint(1_234, -567);

    @Test
    void theFourCardinalAxesStillPointExactlyWhereTheyDid() {
        // Los cardinales pasan por el vector unitario como todos los demás desde que hay diagonales,
        // y hypot(1, 0) vale 1.0 exacto, así que no se les ha colado ningún error de redondeo.
        assertEquals(ORIGIN.x() + 1_000, Destination.highway(Axis.X_PLUS, 1_000).resolve(ORIGIN).x(), 0);
        assertEquals(ORIGIN.z(), Destination.highway(Axis.X_PLUS, 1_000).resolve(ORIGIN).z(), 0);
        assertEquals(ORIGIN.x() - 1_000, Destination.highway(Axis.X_MINUS, 1_000).resolve(ORIGIN).x(), 0);
        assertEquals(ORIGIN.z() + 1_000, Destination.highway(Axis.Z_PLUS, 1_000).resolve(ORIGIN).z(), 0);
        assertEquals(ORIGIN.x(), Destination.highway(Axis.Z_PLUS, 1_000).resolve(ORIGIN).x(), 0);
        assertEquals(ORIGIN.z() - 1_000, Destination.highway(Axis.Z_MINUS, 1_000).resolve(ORIGIN).z(), 0);
    }

    @Test
    void theFourDiagonalAxesPointWhereTheySay() {
        double half = 10_000 * Math.sqrt(0.5);

        assertEquals(ORIGIN.x() + half, Destination.highway(Axis.X_PLUS_Z_PLUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() + half, Destination.highway(Axis.X_PLUS_Z_PLUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);

        assertEquals(ORIGIN.x() + half, Destination.highway(Axis.X_PLUS_Z_MINUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() - half, Destination.highway(Axis.X_PLUS_Z_MINUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);

        assertEquals(ORIGIN.x() - half, Destination.highway(Axis.X_MINUS_Z_PLUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() + half, Destination.highway(Axis.X_MINUS_Z_PLUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);

        assertEquals(ORIGIN.x() - half, Destination.highway(Axis.X_MINUS_Z_MINUS, 10_000).resolve(ORIGIN).x(), TOLERANCE);
        assertEquals(ORIGIN.z() - half, Destination.highway(Axis.X_MINUS_Z_MINUS, 10_000).resolve(ORIGIN).z(), TOLERANCE);
    }

    @Test
    void theHighwayDistanceIsBlocksFlownOnAllEightAxes() {
        // La decisión que gobierna las diagonales: 20 000 es el mismo trozo de fuegos artificiales
        // apunte el eje a donde apunte. Sin normalizar el vector, una diagonal volaría 28 284 -un
        // 41 % de más- con el mismo número puesto, y el jugador se quedaría tirado a mitad de camino.
        for (Axis axis : Axis.values()) {
            Waypoint destination = Destination.highway(axis, 20_000).resolve(ORIGIN);
            assertEquals(20_000, ORIGIN.distanceTo(destination), TOLERANCE,
                axis + " no recorre los bloques que dice");
        }
    }

    @Test
    void onlyTheFourDiagonalsSplitTheDistanceBetweenBothCoordinates() {
        assertFalse(Axis.X_PLUS.isDiagonal());
        assertFalse(Axis.X_MINUS.isDiagonal());
        assertFalse(Axis.Z_PLUS.isDiagonal());
        assertFalse(Axis.Z_MINUS.isDiagonal());
        assertTrue(Axis.X_PLUS_Z_PLUS.isDiagonal());
        assertTrue(Axis.X_PLUS_Z_MINUS.isDiagonal());
        assertTrue(Axis.X_MINUS_Z_PLUS.isDiagonal());
        assertTrue(Axis.X_MINUS_Z_MINUS.isDiagonal());
    }

    @Test
    void everyAxisNameKeepsTheIdentifierMeteorWritesToDisk() {
        // EnumSetting.save escribe get().toString() y load lo busca comparando toString(); si no lo
        // encuentra, parse no asigna nada y el ajuste se queda en su valor de fábrica. O sea que
        // rebautizar un eje -o darle un toString() más bonito- le cambia el eje en silencio a quien
        // tuviera ese guardado. Este test es el que pone en rojo ese cambio antes de que llegue al
        // disco de nadie.
        assertEquals("X_PLUS", Axis.X_PLUS.toString());
        assertEquals("X_MINUS", Axis.X_MINUS.toString());
        assertEquals("Z_PLUS", Axis.Z_PLUS.toString());
        assertEquals("Z_MINUS", Axis.Z_MINUS.toString());
        assertEquals("X_PLUS_Z_PLUS", Axis.X_PLUS_Z_PLUS.toString());
        assertEquals("X_PLUS_Z_MINUS", Axis.X_PLUS_Z_MINUS.toString());
        assertEquals("X_MINUS_Z_PLUS", Axis.X_MINUS_Z_PLUS.toString());
        assertEquals("X_MINUS_Z_MINUS", Axis.X_MINUS_Z_MINUS.toString());
    }

    @Test
    void absoluteCoordinatesIgnoreWhereTheTripStarts() {
        Waypoint destination = Destination.coordinates(5_000, -3_000).resolve(ORIGIN);
        assertEquals(5_000, destination.x(), TOLERANCE);
        assertEquals(-3_000, destination.z(), TOLERANCE);
    }

    @Test
    void aRelativeDestinationIsAnOffsetFromWhereTheTripStarts() {
        Waypoint destination = Destination.relative(5_000, -3_000).resolve(ORIGIN);
        assertEquals(ORIGIN.x() + 5_000, destination.x(), TOLERANCE);
        assertEquals(ORIGIN.z() - 3_000, destination.z(), TOLERANCE);
    }

    @Test
    void theSameTwoNumbersMeanTwoDifferentPlacesInTheTwoModes() {
        // El fallo que los ajustes propios de offset-x/offset-z evitan aguas arriba: los mismos dos
        // números son un sitio del mundo en COORDENADAS y otro completamente distinto en RELATIVO.
        // Que el núcleo los distinga es la mitad de la garantía; la otra mitad es que el adaptador
        // no los lea del mismo par de ajustes.
        Waypoint absolute = Destination.coordinates(5_000, -3_000).resolve(ORIGIN);
        Waypoint offset = Destination.relative(5_000, -3_000).resolve(ORIGIN);

        assertTrue(absolute.distanceTo(offset) > 1,
            "un desplazamiento y unas coordenadas no pueden resolver al mismo punto: " + absolute.distanceTo(offset));
    }

    @Test
    void aZeroOffsetIsTheOriginItself() {
        // Un desplazamiento de (0, 0) es el destino igual al origen, que es el mismo caso que unas
        // coordenadas puestas donde está el jugador o una distancia de autopista de 0. No se rechaza:
        // un viaje de cero bloques es exactamente lo que se pidió, entregado tal cual, y ahí no hay
        // ningún patrón dibujándose recto a espaldas de nadie. Lo comprueba también
        // aRelativeDestinationOfZeroBehavesLikeAnyOtherDestinationEqualToTheOrigin, del planificador.
        Waypoint destination = Destination.relative(0, 0).resolve(ORIGIN);
        assertEquals(ORIGIN.x(), destination.x(), 0);
        assertEquals(ORIGIN.z(), destination.z(), 0);
    }

    @Test
    void onlyTheHighwayModeMakesThePatternStayInsideACorridor() {
        // highway() es lo que mira el planificador para acotar la amplitud y para prohibir el
        // señuelo. El modo relativo es un destino suelto como el de coordenadas: ahí no hay corredor
        // del que salirse, así que no se acota nada.
        assertTrue(Destination.highway(Axis.X_PLUS_Z_MINUS, 1).highway());
        assertFalse(Destination.coordinates(1, 1).highway());
        assertFalse(Destination.relative(1, 1).highway());
    }
}
