package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageTest {
    @Test
    void aValidLineMarksThatChunkAsSeenAndNoOther() {
        Coverage coverage = Coverage.ofLines(List.of("12,-7"));
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertFalse(coverage.seen(new ChunkPos(13, -7)));
        assertEquals(1, coverage.size());
    }

    @Test
    void anEmptyLineIsSkippedNotFatal() {
        Coverage coverage = Coverage.ofLines(List.of("", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aLineOfOnlyWhitespaceIsSkipped() {
        Coverage coverage = Coverage.ofLines(List.of("   ", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aLineWithOnlyOneFieldIsSkipped() {
        // El caso típico de un fichero cortado a mitad de escritura: la coma nunca llegó a salir.
        Coverage coverage = Coverage.ofLines(List.of("4", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aLineWithTextWhereANumberGoesIsSkipped() {
        Coverage coverage = Coverage.ofLines(List.of("abc,9", "4,xyz", "4,9"));
        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void aHalfWrittenFileDoesNotLoseTheLinesThatDidMakeItToDisk() {
        // Simula el cierre brusco: NewerNewChunks va escribiendo línea a línea y el cliente muere
        // a mitad de la última. Las líneas anteriores, completas, tienen que seguir contando.
        Coverage coverage = Coverage.ofLines(List.of("1,1", "2,2", "3,2", "3,"));
        assertEquals(3, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(1, 1)));
        assertTrue(coverage.seen(new ChunkPos(2, 2)));
        assertTrue(coverage.seen(new ChunkPos(3, 2)));
        // La línea rota no puede colarse como si fuera un chunk válido.
        assertFalse(coverage.seen(new ChunkPos(3, 0)));
    }

    @Test
    void aCorruptLineNeverMakesAnAlreadySeenChunkLookUnseen() {
        // El riesgo real: una línea basura no debe "restar" cobertura ya confirmada por otra línea.
        Coverage coverage = Coverage.ofLines(List.of("5,5", "garbage", "5,5"));
        assertTrue(coverage.seen(new ChunkPos(5, 5)));
        assertEquals(1, coverage.size());
    }

    @Test
    void mergeIsTheUnionWithoutDuplicates() {
        Coverage first = Coverage.ofLines(List.of("1,1", "2,2"));
        Coverage second = Coverage.ofLines(List.of("2,2", "3,3"));

        Coverage merged = Coverage.merge(List.of(first, second));

        assertEquals(3, merged.size());
        assertTrue(merged.seen(new ChunkPos(1, 1)));
        assertTrue(merged.seen(new ChunkPos(2, 2)));
        assertTrue(merged.seen(new ChunkPos(3, 3)));
    }

    @Test
    void mergingAnEmptyCollectionIsEmptyNotAnError() {
        // Que no exista ningún fichero -los cinco ausentes- significa empezar de cero, no fallar.
        Coverage merged = Coverage.merge(List.of());
        assertEquals(0, merged.size());
    }

    @Test
    void emptyCoverageSeesNothing() {
        assertFalse(Coverage.empty().seen(new ChunkPos(0, 0)));
        assertEquals(0, Coverage.empty().size());
    }
}
