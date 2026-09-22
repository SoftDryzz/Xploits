package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ---------------------------------------------------------------------------------------
    // ofFileContent: la última línea de un fichero cortado no se puede creer
    // ---------------------------------------------------------------------------------------

    @Test
    void unFicheroTerminadoEnSaltoDeLineaConservaTodasSusLineas() {
        Coverage coverage = Coverage.ofFileContent("12,-7\n4,9\n");

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void unaLineaTruncadaQueParseaNoSeCuelaComoChunkVisto() {
        // El caso exacto: una línea que iba a ser "-412,1087" y que el cierre brusco del cliente
        // dejó en "-412,1". Es un x,z perfectamente válido de un chunk que nunca se vio, así que
        // parseLine no puede cazarlo; si se cuela y era el que le faltaba a su banda, SweepPlanner
        // se salta la banda entera y da por peinada una pasada que no se voló.
        Coverage coverage = Coverage.ofFileContent("100,200\n-412,1");

        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(100, 200)));
        assertFalse(coverage.seen(new ChunkPos(-412, 1)));
        assertFalse(coverage.seen(new ChunkPos(-412, 1087)));
    }

    @Test
    void unFicheroDeUnaSolaLineaSinTerminarNoDejaNadaVisto() {
        // Esa única línea no terminó de escribirse y no hay ninguna anterior: de este fichero no se
        // sabe nada, y "nada" es lo correcto, no el chunk que aparenta.
        assertEquals(0, Coverage.ofFileContent("12,-7").size());
    }

    @Test
    void elFinDeLineaDeWindowsNoCuentaComoDosLineas() {
        Coverage coverage = Coverage.ofFileContent("12,-7\r\n4,9\r\n");

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void unFicheroDeWindowsCortadoTambienPierdeSuUltimaLinea() {
        Coverage coverage = Coverage.ofFileContent("12,-7\r\n4,9");

        assertEquals(1, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertFalse(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void unFicheroVacioEsEmpezarDeCeroYNoUnError() {
        assertEquals(0, Coverage.ofFileContent("").size());
    }

    @Test
    void laBasuraDeEnMedioSeSigueSaltandoSinTirarElResto() {
        Coverage coverage = Coverage.ofFileContent("12,-7\nbasura\n1,2,3\n\n4,9\n");

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(12, -7)));
        assertTrue(coverage.seen(new ChunkPos(4, 9)));
    }

    @Test
    void unContenidoNuloNoSeLeeComoFicheroVacio() {
        assertThrows(NullPointerException.class, () -> Coverage.ofFileContent(null));
    }

    // ---------------------------------------------------------------------------------------
    // seenIn: lo que ahorra la cobertura previa es lo que cae DENTRO del área
    // ---------------------------------------------------------------------------------------

    @Test
    void seenInCuentaSoloLosChunksQueCaenDentroDelArea() {
        // Tres dentro del rectángulo 0,0..2,2 y dos fuera.
        Coverage coverage = Coverage.ofLines(List.of("0,0", "1,1", "2,2", "3,0", "-1,-1"));

        assertEquals(5, coverage.size());
        assertEquals(3, coverage.seenIn(SweepArea.ofChunks(0, 0, 2, 2)));
    }

    @Test
    void seenInNuncaPasaDeLosChunksQueTieneElArea() {
        // El fallo que este método arregla: con size() -la cobertura de toda la dimensión- el
        // mensaje llegaba a anunciar más chunks vistos que chunks tiene el área. Aquí hay 9 chunks
        // de área y 12 vistos en total.
        Coverage coverage = Coverage.ofLines(List.of(
            "0,0", "0,1", "0,2", "1,0", "1,1", "1,2", "2,0", "2,1", "2,2",
            "50,50", "51,50", "52,50"));
        SweepArea area = SweepArea.ofChunks(0, 0, 2, 2);

        assertEquals(12, coverage.size());
        assertEquals(area.chunkCount(), coverage.seenIn(area));
    }

    @Test
    void seenInSobreUnAreaSinNadaVistoEsCero() {
        Coverage coverage = Coverage.ofLines(List.of("50,50"));

        assertEquals(0, coverage.seenIn(SweepArea.ofChunks(0, 0, 9, 9)));
    }

    @Test
    void seenInCuentaLosBordesDelArea() {
        // Las cuatro esquinas entran: el área incluye ambos bordes de cada eje.
        Coverage coverage = Coverage.ofLines(List.of("0,0", "0,3", "3,0", "3,3"));

        assertEquals(4, coverage.seenIn(SweepArea.ofChunks(0, 0, 3, 3)));
    }

    @Test
    void seenInNecesitaUnArea() {
        assertThrows(NullPointerException.class, () -> Coverage.empty().seenIn(null));
    }

    // ---------------------------------------------------------------------------------------
    // La promesa de saltarse "cualquier otra cosa" se cumple entera, nulos incluidos
    // ---------------------------------------------------------------------------------------

    @Test
    void unaLineaNulaSeSaltaComoCualquierOtraLineaMala() {
        // El javadoc promete saltarse "cualquier otra cosa" y seguir con las siguientes. Un nulo
        // rompia esa promesa desde dentro del camino cuyo proposito declarado es no abortar nunca
        // la lectura por una linea mala.
        List<String> lineas = new ArrayList<>();
        lineas.add("1,1");
        lineas.add(null);
        lineas.add("2,2");

        Coverage coverage = Coverage.ofLines(lineas);

        assertEquals(2, coverage.size());
        assertTrue(coverage.seen(new ChunkPos(1, 1)));
        assertTrue(coverage.seen(new ChunkPos(2, 2)));
    }

    @Test
    void ofLinesSinLineasQueLeerSeQuejaEnVezDeFingirUnFicheroVacio() {
        assertThrows(NullPointerException.class, () -> Coverage.ofLines(null));
    }

    @Test
    void unaLecturaNulaNoSeLlevaPorDelanteLasDemas() {
        // Son cinco ficheros y la union es la respuesta a "se ha visto ya este chunk?": que de uno
        // no haya salido nada no puede tirar lo que si salio de los otros, porque una cobertura
        // leida vacia significa replanificar horas de terreno ya visto.
        List<Coverage> partes = new ArrayList<>();
        partes.add(Coverage.ofLines(List.of("1,1")));
        partes.add(null);
        partes.add(Coverage.ofLines(List.of("2,2")));

        Coverage union = Coverage.merge(partes);

        assertEquals(2, union.size());
        assertTrue(union.seen(new ChunkPos(1, 1)));
        assertTrue(union.seen(new ChunkPos(2, 2)));
    }

    @Test
    void mergeSinNadaQueUnirSeQuejaEnVezDeFingirQueNoHabiaFicheros() {
        assertThrows(NullPointerException.class, () -> Coverage.merge(null));
    }
}
