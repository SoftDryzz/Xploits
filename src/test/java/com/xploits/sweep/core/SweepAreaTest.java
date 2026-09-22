package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------------------------------------------------------------------------------------
    // El tope de tamaño: dos recorridos del módulo cuestan el rectángulo entero
    // ---------------------------------------------------------------------------------------

    @Test
    void unAreaNormalNoSeRechazaPorTamano() {
        // La caja entera que el jugador ha cruzado en meses en este servidor, 384x580 chunks
        // (spec §1): 222.720 chunks, muy por debajo del tope. Si esto se rechazara, el tope estaría
        // estorbando al uso que justifica el módulo.
        SweepArea area = SweepArea.ofChunks(0, 0, 383, 579);

        assertEquals(222_720, area.chunkCount());
        assertNull(area.oversizeRejection());
    }

    @Test
    void unAreaJustoEnElTopeSeAcepta() {
        // 2.000 x 2.000 = 4.000.000 clavados. El borde entra: el tope es "como mucho esto", no
        // "menos que esto".
        SweepArea area = SweepArea.ofChunks(0, 0, 1_999, 1_999);

        assertEquals(SweepArea.MAXIMO_DE_CHUNKS, area.chunkCount());
        assertNull(area.oversizeRejection());
    }

    @Test
    void unChunkPorEncimaDelTopeYaSeRechaza() {
        // 2.000 x 2.001 = 4.002.000, apenas un 0,05 % por encima. El corte está donde dice estar.
        SweepArea area = SweepArea.ofChunks(0, 0, 1_999, 2_000);

        assertNotNull(area.oversizeRejection());
    }

    @Test
    void elAreaDelLimiteDelDeslizadorSeRechazaSinIntentarRecorrerla() {
        // 20.000 x 20.000 chunks es lo que dan las esquinas en los extremos del deslizador de los
        // ajustes: 400 millones de chunks. Antes de este tope, Coverage.seenIn y SweepPlanner los
        // visitaban uno a uno en el hilo principal desde un comando.
        SweepArea area = SweepArea.ofChunks(-10_000, -10_000, 9_999, 9_999);

        String motivo = area.oversizeRejection();
        assertNotNull(motivo);
        assertTrue(motivo.contains("400000000"), motivo);
    }

    @Test
    void unAreaQueNiSiquieraCabeEnUnIntSeRechazaEnVezDeLanzar() {
        // 50.001 x 50.001 = 2.500.100.001 chunks: el área que hace lanzar a chunkCount(). El tope
        // tiene que poder contestar precisamente sobre ella, así que la cuenta va en long y no
        // llamando a chunkCount().
        SweepArea area = SweepArea.ofChunks(0, 0, 50_000, 50_000);

        assertThrows(ArithmeticException.class, area::chunkCount);
        assertNotNull(area.oversizeRejection());
    }

    @Test
    void elMotivoDiceElTamanoElTopeYQueAjusteTocar() {
        // Mismo estilo que los demás rechazos del módulo: qué pasa, cuánto vale ahora y qué tocar,
        // con los ajustes nombrados como aparecen en la interfaz.
        SweepArea area = SweepArea.ofChunks(0, 0, 2_999, 2_999);

        String motivo = area.oversizeRejection();
        assertTrue(motivo.contains("3000x3000"), motivo);
        assertTrue(motivo.contains("9000000"), motivo);
        assertTrue(motivo.contains(String.valueOf(SweepArea.MAXIMO_DE_CHUNKS)), motivo);
        assertTrue(motivo.contains("chunk-x-1"), motivo);
        assertTrue(motivo.contains("chunk-z-2"), motivo);
    }

    @Test
    void elMotivoDiceACuantoBajarElLadoLargoManteniendoElCorto() {
        // 8.000 de ancho por 1.000 de alto: manteniendo el lado corto en 1.000, el largo no puede
        // pasar de 4.000.000 / 1.000 = 4.000. Ese es el número accionable.
        SweepArea area = SweepArea.ofChunks(0, 0, 7_999, 999);

        String motivo = area.oversizeRejection();
        assertTrue(motivo.contains("no puede pasar de 4000"), motivo);
    }

    @Test
    void siElLadoCortoYaSePasaSoloSeDiceQueAcerqueLasDosEsquinas() {
        // 5.000 x 5.000: el lado corto son 5.000 chunks y 4.000.000 / 5.000 = 800, que es MENOS que
        // el propio lado corto. Decirle "baja el largo a 800" sería mandarle a un rectángulo que
        // sigue sin caber, así que aquí el consejo es otro.
        SweepArea area = SweepArea.ofChunks(0, 0, 4_999, 4_999);

        String motivo = area.oversizeRejection();
        assertTrue(motivo.contains("acerca las dos"), motivo);
        assertFalse(motivo.contains("no puede pasar de"), motivo);
    }
}
