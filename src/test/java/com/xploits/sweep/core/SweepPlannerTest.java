package com.xploits.sweep.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del planificador de pasadas (spec Nether Sweep §10).
 *
 * <p>Todas las coordenadas de estos tests son inventadas y pequeñas: lo que se comprueba es
 * geometría, no ningún sitio concreto del mundo.
 */
class SweepPlannerTest {
    private static final int BLOQUES_POR_CHUNK = 16;

    // ---------------------------------------------------------------------------------------
    // La propiedad que de verdad importa: nada del área queda sin mirar
    // ---------------------------------------------------------------------------------------

    @Test
    void conCoberturaVaciaNingunChunkDelAreaQuedaLejosDeAlgunaPasada() {
        // Se barren TODOS los chunks del área y se mide cada uno contra las pasadas. Contar las
        // pasadas no demostraría nada: veinte pasadas mal colocadas dejan franjas sin ver igual.
        SweepArea area = SweepArea.ofChunks(0, 0, 39, 27);
        int anchuraDePasada = 5;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), anchuraDePasada);

        assertFalse(plan.isRejected());
        assertTodoElAreaQuedaCubierta(area, plan.lanes(), anchuraDePasada);
    }

    @Test
    void laCoberturaTambienSaleConAnchuraParYConUnaBandaFinalIncompleta() {
        // Anchura par (el centro de banda cae entre dos chunks) y un eje que no es múltiplo de la
        // anchura, así que la última banda sale más estrecha que las demás.
        SweepArea area = SweepArea.ofChunks(-7, 3, 18, 30);
        int anchuraDePasada = 4;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), anchuraDePasada);

        assertFalse(plan.isRejected());
        assertTodoElAreaQuedaCubierta(area, plan.lanes(), anchuraDePasada);
    }

    @Test
    void unAreaMasEstrechaQueUnaPasadaDaUnaPasadaNoCero() {
        SweepArea area = SweepArea.ofChunks(10, 10, 11, 11);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 16);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
        assertTodoElAreaQuedaCubierta(area, plan.lanes(), 16);
    }

    @Test
    void unAreaDeUnSoloChunkTambienDaUnaPasada() {
        SweepArea area = SweepArea.ofChunks(-4, 9, -4, 9);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 8);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
    }

    // ---------------------------------------------------------------------------------------
    // Planificar solo sobre lo que falta por ver (spec §5.1)
    // ---------------------------------------------------------------------------------------

    @Test
    void unAreaYaCubiertaEnteraDaCeroPasadasYNoSeRechaza() {
        // No hay nada que volar, y eso no es un error: es el barrido terminado.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        Coverage vista = coberturaDe(area);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 4);

        assertFalse(plan.isRejected());
        assertTrue(plan.lanes().isEmpty());
        assertEquals(0.0, plan.totalBlocks());
    }

    @Test
    void unAreaCubiertaAMediasSoloProducePasadasSobreLosHuecos() {
        // Área cuadrada: las pasadas van en X y se apilan en Z. Con anchura 4 salen cuatro bandas
        // en Z (0-3, 4-7, 8-11, 12-15) y las dos primeras están vistas enteras.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        Coverage vista = coberturaDe(SweepArea.ofChunks(0, 0, 15, 7));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 4);

        assertFalse(plan.isRejected());
        assertEquals(2, plan.lanes().size());
        for (Lane pasada : plan.lanes()) {
            // Ninguna pasada se gasta sobre la mitad ya vista.
            assertTrue(pasada.fromZ() > 7 * BLOQUES_POR_CHUNK,
                "una pasada cayó sobre terreno ya visto: " + pasada);
        }
    }

    @Test
    void unHuecoMasEstrechoQueUnaPasadaNoSeIgnora() {
        // Un único chunk sin ver dentro de una banda de ocho. Descartarlo por pequeño ahorraría
        // una pasada entera y dejaría ese chunk marcado como peinado sin haberlo mirado nunca:
        // es el fallo que este módulo no puede cometer.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        ChunkPos hueco = new ChunkPos(5, 3);
        Coverage vista = coberturaDeSalvo(area, hueco);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 8);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
        assertTrue(distanciaMinima(hueco, plan.lanes()) <= 8 / 2.0 * BLOQUES_POR_CHUNK,
            "el único chunk sin ver quedó fuera del alcance de las pasadas");
    }

    @Test
    void unHuecoDeUnChunkEnCadaBandaProduceUnaPasadaPorBanda() {
        // El mismo caso repartido: dos huecos de un chunk, uno en cada banda. Ninguno se ignora.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);
        ChunkPos huecoArriba = new ChunkPos(2, 1);
        ChunkPos huecoAbajo = new ChunkPos(13, 14);
        Coverage vista = coberturaDeSalvo(area, huecoArriba, huecoAbajo);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 8);

        assertEquals(2, plan.lanes().size());
        assertTrue(distanciaMinima(huecoArriba, plan.lanes()) <= 8 / 2.0 * BLOQUES_POR_CHUNK);
        assertTrue(distanciaMinima(huecoAbajo, plan.lanes()) <= 8 / 2.0 * BLOQUES_POR_CHUNK);
    }

    // ---------------------------------------------------------------------------------------
    // El sentido alterna, y alterna sobre las pasadas que se vuelan
    // ---------------------------------------------------------------------------------------

    @Test
    void lasPasadasAlternanElSentidoParaNoVolverEnVacio() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        assertLasPasadasEncadenan(plan.lanes());
    }

    @Test
    void laAlternanciaCuentaLasPasadasVoladasNoLasBandasSaltadas() {
        // Con una banda entera ya vista, alternar por número de banda dejaría dos pasadas seguidas
        // en el mismo sentido y el jugador volvería el largo del área en vacío entre ellas.
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 23);
        Coverage vista = coberturaDe(SweepArea.ofChunks(0, 4, 31, 7));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 4);

        assertEquals(5, plan.lanes().size());
        assertLasPasadasEncadenan(plan.lanes());
    }

    // ---------------------------------------------------------------------------------------
    // Las pasadas van paralelas al eje largo
    // ---------------------------------------------------------------------------------------

    @Test
    void enUnAreaMasAnchaQueAltaLasPasadasVanEnX() {
        SweepArea area = SweepArea.ofChunks(0, 0, 63, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        for (Lane pasada : plan.lanes()) {
            assertEquals(pasada.fromZ(), pasada.toZ(), "una pasada no salió paralela al eje X");
        }
    }

    @Test
    void enUnAreaMasAltaQueAnchaLasPasadasVanEnZ() {
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 63);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        for (Lane pasada : plan.lanes()) {
            assertEquals(pasada.fromX(), pasada.toX(), "una pasada no salió paralela al eje Z");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Anchura de pasada inservible: se rechaza con motivo, no se degrada
    // ---------------------------------------------------------------------------------------

    @Test
    void anchuraDePasadaCeroSeRechazaEnVezDeDegradarse() {
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 0);

        assertTrue(plan.isRejected());
        assertTrue(plan.lanes().isEmpty());
        assertEquals(0.0, plan.totalBlocks());
    }

    @Test
    void anchuraDePasadaNegativaSeRechazaEnVezDeDegradarse() {
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), -3);

        assertTrue(plan.isRejected());
        assertTrue(plan.lanes().isEmpty());
    }

    @Test
    void elMotivoDelRechazoNombraElAjusteSuValorYAQueSubirlo() {
        // Estilo de travel/core/RoutePlanner: el motivo dice qué tocar y a qué valor, no solo que
        // algo está mal.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        String motivoCero = SweepPlanner.plan(area, Coverage.empty(), 0).rejection();
        assertTrue(motivoCero.contains("anchura de pasada"), motivoCero);
        assertTrue(motivoCero.contains("0"), motivoCero);
        assertTrue(motivoCero.contains("1 chunk"), motivoCero);

        String motivoNegativo = SweepPlanner.plan(area, Coverage.empty(), -3).rejection();
        assertTrue(motivoNegativo.contains("anchura de pasada"), motivoNegativo);
        assertTrue(motivoNegativo.contains("-3"), motivoNegativo);
        assertTrue(motivoNegativo.contains("1 chunk"), motivoNegativo);
    }

    // ---------------------------------------------------------------------------------------
    // El plan como valor
    // ---------------------------------------------------------------------------------------

    @Test
    void totalBlocksSumaLaLongitudDeTodasLasPasadas() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 15);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        double esperado = 0.0;
        for (Lane pasada : plan.lanes()) {
            esperado += pasada.lengthInBlocks();
        }
        assertEquals(esperado, plan.totalBlocks(), 1e-9);
        assertTrue(plan.totalBlocks() > 0.0);
    }

    @Test
    void unPlanRechazadoNoPuedeLlevarPasadas() {
        // Nadie debe poder construir un plan que diga "rechazado" y a la vez traiga pasadas: quien
        // leyera solo la lista volaría un barrido que se había rechazado.
        List<Lane> pasadas = List.of(new Lane(0, 0, 100, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new SweepPlanner.SweepPlan(pasadas, "un motivo cualquiera"));
    }

    @Test
    void laListaDePasadasDelPlanEsInmutable() {
        SweepPlanner.SweepPlan plan =
            SweepPlanner.plan(SweepArea.ofChunks(0, 0, 15, 15), Coverage.empty(), 4);

        assertThrows(UnsupportedOperationException.class,
            () -> plan.lanes().add(new Lane(0, 0, 1, 1)));
    }

    // ---------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------

    /** Cobertura con todos los chunks del área marcados como vistos. */
    private static Coverage coberturaDe(SweepArea area) {
        return Coverage.ofLines(lineasDe(area, new ChunkPos[0]));
    }

    /** Cobertura con todos los chunks del área vistos salvo los huecos indicados. */
    private static Coverage coberturaDeSalvo(SweepArea area, ChunkPos... huecos) {
        return Coverage.ofLines(lineasDe(area, huecos));
    }

    private static List<String> lineasDe(SweepArea area, ChunkPos[] huecos) {
        List<ChunkPos> excluidos = List.of(huecos);
        List<String> lineas = new ArrayList<>();
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (!excluidos.contains(chunk)) {
                    lineas.add(x + "," + z);
                }
            }
        }
        return lineas;
    }

    /**
     * Ningún chunk del área puede quedar a más de media anchura de pasada de alguna pasada. Es la
     * propiedad de cobertura entera: si un solo chunk la incumple, hay terreno que el barrido daría
     * por peinado sin haberlo mirado.
     */
    private static void assertTodoElAreaQuedaCubierta(SweepArea area, List<Lane> pasadas,
                                                      int anchuraDePasada) {
        double alcance = anchuraDePasada / 2.0 * BLOQUES_POR_CHUNK;
        for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
            for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                double distancia = distanciaMinima(chunk, pasadas);
                assertTrue(distancia <= alcance + 1e-9,
                    "el chunk " + x + "," + z + " quedó a " + distancia + " bloques de la pasada más"
                        + " cercana, y el alcance de una pasada es " + alcance);
            }
        }
    }

    /**
     * Las pasadas se vuelan en secuencia, así que cada una tiene que arrancar donde terminó la
     * anterior en el eje largo; si no, el jugador recorre el área entera en vacío entre pasada y
     * pasada.
     */
    private static void assertLasPasadasEncadenan(List<Lane> pasadas) {
        for (int i = 0; i + 1 < pasadas.size(); i++) {
            Lane actual = pasadas.get(i);
            Lane siguiente = pasadas.get(i + 1);
            assertEquals(actual.toX(), siguiente.fromX(), 1e-9,
                "la pasada " + (i + 1) + " no arranca en X donde terminó la " + i);
            assertTrue(actual.fromX() != actual.toX() || actual.fromZ() != actual.toZ());
            assertEquals(esDeIda(actual), !esDeIda(siguiente),
                "dos pasadas seguidas en el mismo sentido: se vuelve en vacío entre ellas");
        }
    }

    private static boolean esDeIda(Lane pasada) {
        return pasada.fromX() < pasada.toX() || pasada.fromZ() < pasada.toZ();
    }

    /** Distancia en bloques del centro del chunk al segmento de pasada más cercano. */
    private static double distanciaMinima(ChunkPos chunk, List<Lane> pasadas) {
        double px = chunk.x() * (double) BLOQUES_POR_CHUNK + BLOQUES_POR_CHUNK / 2.0;
        double pz = chunk.z() * (double) BLOQUES_POR_CHUNK + BLOQUES_POR_CHUNK / 2.0;
        double minima = Double.POSITIVE_INFINITY;
        for (Lane pasada : pasadas) {
            minima = Math.min(minima, distanciaAlSegmento(px, pz, pasada));
        }
        return minima;
    }

    private static double distanciaAlSegmento(double px, double pz, Lane pasada) {
        double dx = pasada.toX() - pasada.fromX();
        double dz = pasada.toZ() - pasada.fromZ();
        double longitudCuadrada = dx * dx + dz * dz;
        double t = longitudCuadrada == 0.0
            ? 0.0
            : Math.max(0.0, Math.min(1.0,
                ((px - pasada.fromX()) * dx + (pz - pasada.fromZ()) * dz) / longitudCuadrada));
        return Math.hypot(px - (pasada.fromX() + t * dx), pz - (pasada.fromZ() + t * dz));
    }
}
