package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SweepAreaTest {
    @Test
    void widthAndHeightCountBothChunkEdgesInclusive() {
        SweepArea area = SweepArea.ofChunks(0, 0, 3, 3);
        assertEquals(4, area.widthInChunks());
        assertEquals(4, area.heightInChunks());
        assertEquals(16, area.chunkCount());
    }

    @Test
    void aSingleChunkIsStillOneByOne() {
        SweepArea area = SweepArea.ofChunks(5, -2, 5, -2);
        assertEquals(1, area.widthInChunks());
        assertEquals(1, area.heightInChunks());
        assertEquals(1, area.chunkCount());
    }

    @Test
    void givingTheGreaterCornerFirstDoesNotProduceAnEmptyArea() {
        // Es el error de dedo más probable: min y max intercambiados en cada eje.
        SweepArea straight = SweepArea.ofChunks(0, 0, 3, 3);
        SweepArea swapped = SweepArea.ofChunks(3, 3, 0, 0);
        assertEquals(straight, swapped);
    }

    @Test
    void cornersNormalizePerAxisIndependently() {
        // Un eje al revés y el otro no también debe normalizarse.
        SweepArea area = SweepArea.ofChunks(4, -1, -2, 5);
        assertEquals(-2, area.minChunkX());
        assertEquals(4, area.maxChunkX());
        assertEquals(-1, area.minChunkZ());
        assertEquals(5, area.maxChunkZ());
    }

    @Test
    void overworldEquivalentMultipliesChunksBySixteenAndThenByEight() {
        // 4 chunks de ancho/alto en el Nether: 4 * 16 * 8 = 512 bloques en el Overworld.
        SweepArea area = SweepArea.ofChunks(0, 0, 3, 3);
        assertEquals("512x512 bloques del Overworld", area.overworldEquivalent());
    }

    @Test
    void overworldEquivalentIsIndependentPerAxis() {
        SweepArea area = SweepArea.ofChunks(0, 0, 1, 3);
        assertEquals("256x512 bloques del Overworld", area.overworldEquivalent());
    }

    @Test
    void chunkCountFitsAsAnIntForALargeButRealisticArea() {
        // 40000x40000 chunks: grande de verdad, pero el producto (1.600.000.000) todavía cabe en
        // un int, así que no debe lanzar ni desbordar.
        SweepArea area = SweepArea.ofChunks(0, 0, 39_999, 39_999);
        assertEquals(1_600_000_000, area.chunkCount());
    }

    @Test
    void chunkCountRejectsAnAreaTooBigToCountAsAnInt() {
        // 50001x50001 chunks: tecleable, muy por debajo del límite real del mundo (~±3.750.000 en
        // chunks), pero el producto (2.500.100.001) ya no cabe en un int. Antes del arreglo esto
        // desbordaba en silencio a -1.794.867.295; ahora debe fallar alto, no devolver un número
        // negativo con pinta de válido.
        SweepArea area = SweepArea.ofChunks(0, 0, 50_000, 50_000);
        assertThrows(ArithmeticException.class, area::chunkCount);
    }

    @Test
    void theCanonicalConstructorRejectsInvertedCorners() {
        // A diferencia de ofChunks, el constructor canónico no reordena: los nombres min/max
        // prometen un orden, y aceptarlo al revés en silencio es justo el bug que se arregla aquí
        // -antes daba chunkCount()=16 (positivo) y overworldEquivalent()="-512x-512" a la vez-.
        assertThrows(IllegalArgumentException.class, () -> new SweepArea(5, 5, 0, 0));
    }

    @Test
    void theCanonicalConstructorRejectsWhenOnlyOneAxisIsInverted() {
        // El eje Z está bien (min -1 <= max 3); solo el X viene invertido (min 5 > max 0). Debe
        // rechazarse igual: no basta con que un eje esté bien para salvar el área entera.
        assertThrows(IllegalArgumentException.class, () -> new SweepArea(5, -1, 0, 3));
    }

    @Test
    void laneLengthIsTheEuclideanDistanceBetweenItsEnds() {
        // 300-400-500: la terna pitagórica de toda la vida, fácil de verificar a ojo.
        Lane lane = new Lane(0, 0, 300, 400);
        assertEquals(500, lane.lengthInBlocks(), 0.0001);
    }

    @Test
    void laneLengthWorksWhenNeitherAxisIsZero() {
        Lane lane = new Lane(1000, 2000, 1300, 1600);
        assertEquals(500, lane.lengthInBlocks(), 0.0001);
    }

    @Test
    void aZeroLengthLaneHasZeroLength() {
        // T3 suma estas longitudes para decidir si despegar: una pasada degenerada no puede
        // colarse como si tuviera coste, ni tampoco romper la suma.
        Lane lane = new Lane(1500, -700, 1500, -700);
        assertEquals(0, lane.lengthInBlocks(), 0.0001);
    }
}
