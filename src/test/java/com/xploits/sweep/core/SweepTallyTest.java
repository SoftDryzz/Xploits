package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la cuenta de cobertura del barrido: que al terminar se sepa <b>cuánto se miró</b> y no
 * solo que se terminó (spec Nether Sweep §9).
 *
 * <p>Todas las coordenadas de estos tests son inventadas y pequeñas: lo que se comprueba es
 * aritmética de rectángulos, no ningún sitio concreto del mundo.
 */
class SweepTallyTest {
    private static final Catalog ES = Catalog.load(Language.ES, p -> {
        throw new AssertionError(p);
    });

    /** A text as the player reads it in Spanish, for the tests that look for words and numbers in it. */
    private static String es(Msg msg) {
        return msg == null ? "null" : ES.render(msg);
    }

    private static final SweepArea AREA = new SweepArea(0, 0, 9, 9);

    @Test
    void sinCoberturaPreviaNiChunksRecibidosNoHayNadaCubierto() {
        SweepTally cuenta = SweepTally.of(AREA, Coverage.empty());

        assertEquals(100, cuenta.areaChunks());
        assertEquals(0, cuenta.alreadySeen());
        assertEquals(0, cuenta.arrived());
        assertEquals(0, cuenta.covered());
        assertEquals(100, cuenta.missing());
        assertEquals(0.0, cuenta.coveredFraction(), 1e-9);
    }

    @Test
    void laCoberturaPreviaDelAreaCuentaDesdeElPrincipio() {
        // Las bandas ya vistas enteras se las salta el planificador, así que sus chunks no van a
        // llegar durante el vuelo: contarlos como huecos haría que todo barrido sobre un área medio
        // conocida pareciera fallido.
        SweepTally cuenta = SweepTally.of(AREA, cubrirFila(0));

        assertEquals(10, cuenta.alreadySeen());
        assertEquals(10, cuenta.covered());
        assertEquals(0, cuenta.arrived());
        assertEquals(90, cuenta.missing());
    }

    @Test
    void laCoberturaPreviaDeFueraDelAreaNoCuenta() {
        Coverage fuera = Coverage.ofLines(List.of("500,500", "-40,7"));

        SweepTally cuenta = SweepTally.of(AREA, fuera);

        assertEquals(0, cuenta.alreadySeen());
        assertEquals(100, cuenta.missing());
    }

    @Test
    void unChunkRecibidoDentroDelAreaCuenta() {
        SweepTally cuenta = SweepTally.of(AREA, Coverage.empty());

        assertTrue(cuenta.record(4, 4));

        assertEquals(1, cuenta.covered());
        assertEquals(1, cuenta.arrived());
        assertEquals(99, cuenta.missing());
    }

    @Test
    void unChunkRecibidoFueraDelAreaNoCuenta() {
        // Durante la aproximación llegan miles, y ninguno es terreno del rectángulo pedido.
        SweepTally cuenta = SweepTally.of(AREA, Coverage.empty());

        assertFalse(cuenta.record(-1, 4));
        assertFalse(cuenta.record(4, 10));
        assertFalse(cuenta.record(1_000, 1_000));

        assertEquals(0, cuenta.covered());
    }

    @Test
    void elMismoChunkDosVecesNoCuentaDosVeces() {
        // El servidor reenvía chunks al volver a pasar por encima: sin esto, un barrido que pasara
        // dos veces por media banda anunciaría más cobertura de la que hay.
        SweepTally cuenta = SweepTally.of(AREA, Coverage.empty());

        assertTrue(cuenta.record(4, 4));
        assertFalse(cuenta.record(4, 4));

        assertEquals(1, cuenta.covered());
    }

    @Test
    void unChunkQueYaEstabaVistoNoSeCuentaOtraVezAlLlegar() {
        SweepTally cuenta = SweepTally.of(AREA, cubrirFila(0));

        assertFalse(cuenta.record(0, 3));

        assertEquals(10, cuenta.covered());
        assertEquals(0, cuenta.arrived());
    }

    @Test
    void cadaEsquinaDelAreaTieneSuPropioSitio() {
        // Si el índice del BitSet se desalineara, dos chunks distintos compartirían bit y la cuenta
        // final mentiría en silencio, que es justo el fallo que esta clase existe para no repetir.
        SweepArea rectangulo = new SweepArea(-5, 7, -2, 11);
        SweepTally cuenta = SweepTally.of(rectangulo, Coverage.empty());

        for (int x = -5; x <= -2; x++) {
            for (int z = 7; z <= 11; z++) {
                assertTrue(cuenta.record(x, z), "el chunk " + x + "," + z + " tenía que ser nuevo");
            }
        }

        assertEquals(rectangulo.chunkCount(), cuenta.covered());
        assertEquals(0, cuenta.missing());
    }

    @Test
    void unBarridoQueNoHaVistoLaMitadSeQuedaPorDebajoDelSuelo() {
        SweepTally cuenta = SweepTally.of(AREA, Coverage.empty());
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 9; z++) {
                cuenta.record(x, z);
            }
        }

        assertEquals(0.5, cuenta.coveredFraction(), 1e-9);
        assertTrue(cuenta.shortOfCoverage(0.95));
        assertFalse(cuenta.shortOfCoverage(0.5), "el suelo es un mínimo, no un umbral estricto");
    }

    @Test
    void unAreaCubiertaEnteraNoSeQuedaCortaConNingunSuelo() {
        SweepTally cuenta = SweepTally.of(AREA, Coverage.empty());
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 9; z++) {
                cuenta.record(x, z);
            }
        }

        assertEquals(1.0, cuenta.coveredFraction(), 1e-9);
        assertFalse(cuenta.shortOfCoverage(1.0));
        assertTrue(es(cuenta.summary()).contains("100 %"));
        assertTrue(es(cuenta.summary()).contains("No falta ninguno"));
    }

    @Test
    void elPorcentajeSeRedondeaHaciaAbajoParaNoAnunciarUnCienConHuecos() {
        // 999 de 1.000 son 99,9 %: redondeado al más cercano saldría "100 %" con un chunk sin ver,
        // que es la misma mentira de siempre redactada por el redondeo.
        SweepArea mil = new SweepArea(0, 0, 24, 39);
        SweepTally cuenta = SweepTally.of(mil, Coverage.empty());
        int puestos = 0;
        for (int x = 0; x <= 24 && puestos < 999; x++) {
            for (int z = 0; z <= 39 && puestos < 999; z++) {
                cuenta.record(x, z);
                puestos++;
            }
        }

        assertEquals(1, cuenta.missing());
        assertTrue(es(cuenta.summary()).contains("99 %"), es(cuenta.summary()));
        assertFalse(es(cuenta.summary()).contains("100 %"), es(cuenta.summary()));
    }

    @Test
    void elResumenDiceCuantosLlegaronDeCuantosYDeDondeSalen() {
        SweepTally cuenta = SweepTally.of(AREA, cubrirFila(0));
        cuenta.record(5, 5);
        cuenta.record(5, 6);

        String resumen = es(cuenta.summary());

        assertTrue(resumen.contains("12 de 100"), resumen);
        assertTrue(resumen.contains("12 %"), resumen);
        assertTrue(resumen.contains("10 que ya estaban"), resumen);
        assertTrue(resumen.contains("2 que han llegado"), resumen);
        assertTrue(resumen.contains("88 chunks"), resumen);
    }

    @Test
    void sinAreaOSinCoberturaPreviaSeLanzaEnVezDeContarSobreLaNada() {
        assertThrows(NullPointerException.class, () -> SweepTally.of(null, Coverage.empty()));
        assertThrows(NullPointerException.class, () -> SweepTally.of(AREA, null));
    }

    /** Una cobertura previa con la fila {@code x} entera del área ya vista. */
    private static Coverage cubrirFila(int x) {
        List<String> lineas = new ArrayList<>();
        for (int z = 0; z <= 9; z++) {
            lineas.add(x + "," + z);
        }
        return Coverage.ofLines(lineas);
    }
}
