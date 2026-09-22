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
    void conElEjeApiladoMultiploExactoDeLaAnchuraLaPasadaTieneQueIrEnElCentroDeLaBanda() {
        // Los dos casos de arriba miden la SEPARACIÓN entre pasadas, pero casi no miden dónde cae
        // cada una: la tolerancia (media anchura) coincide con media separación, así que una pasada
        // corrida dentro de su banda queda tapada por la pasada vecina. Salvo en la última banda,
        // que no tiene vecina por fuera -y las dos áreas de arriba la tienen corta, que es justo la
        // forma que lo esconde-.
        //
        // Con el eje apilado múltiplo exacto de la anchura, la última banda va completa y su borde
        // exterior queda expuesto: colocar la pasada al principio de la banda en vez de al centro
        // deja ese borde a (W-1) chunks de la pasada más cercana, por encima de los W/2 de alcance
        // para cualquier W > 2. Con W=5 eso son dos filas de chunks por banda sin mirar en todo el
        // barrido.
        SweepArea area = SweepArea.ofChunks(0, 0, 39, 19);
        int anchuraDePasada = 5;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), anchuraDePasada);

        assertFalse(plan.isRejected());
        assertEquals(4, plan.lanes().size());
        assertTodoElAreaQuedaCubierta(area, plan.lanes(), anchuraDePasada);
    }

    @Test
    void elCentroDeLaBandaTambienMandaConAnchuraParYEjeMultiploExacto() {
        // El mismo caso con anchura par: el centro de banda cae entre dos chunks y la banda final va
        // completa, así que su borde exterior también queda sin vecina que lo tape.
        SweepArea area = SweepArea.ofChunks(0, 0, 39, 15);
        int anchuraDePasada = 4;

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), anchuraDePasada);

        assertFalse(plan.isRejected());
        assertEquals(4, plan.lanes().size());
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
    void unAreaDeUnSoloChunkDaUnaPasadaVolableNoUnPuntoDoble() {
        // Con los extremos sobre el centro del primer y del último chunk del eje largo, un área de
        // 1x1 los dejaría encima: una "pasada" de longitud cero, que no es una instrucción de vuelo
        // sino un vector nulo y un objetivo idéntico al origen para Baritone. No emitirla tampoco
        // vale: ese chunk se quedaría sin ver y el barrido lo daría por peinado igual.
        SweepArea area = SweepArea.ofChunks(-4, 9, -4, 9);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 8);

        assertFalse(plan.isRejected());
        assertEquals(1, plan.lanes().size());
        Lane pasada = plan.lanes().get(0);
        assertTrue(pasada.fromX() != pasada.toX() || pasada.fromZ() != pasada.toZ(),
            "la pasada salió con los dos extremos en el mismo punto: " + pasada);
        assertEquals(BLOQUES_POR_CHUNK, pasada.lengthInBlocks(), 1e-9,
            "la pasada de un área de un solo chunk debería medir el chunk entero: " + pasada);
        // Y el chunk sigue cayendo sobre la pasada: darle longitud no puede mover la cobertura.
        assertEquals(0.0, distanciaMinima(new ChunkPos(-4, 9), plan.lanes()), 1e-9);
        assertEquals(BLOQUES_POR_CHUNK, plan.totalBlocks(), 1e-9);
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
    void lasPasadasEnZTambienAlternanElSentido() {
        // La rama Z de la colocación es código aparte de la rama X, y sin este caso no la miraba
        // nadie: sin alternar, en esta área el jugador volaría más de tres mil bloques en vacío
        // -tres enlaces del largo entero del área- y ningún test lo diría.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 63);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(4, plan.lanes().size());
        for (Lane pasada : plan.lanes()) {
            assertEquals(pasada.fromX(), pasada.toX(), "esta prueba asume pasadas paralelas a Z");
        }
        assertLasPasadasEncadenan(plan.lanes());
    }

    @Test
    void enZLaAlternanciaTambienCuentaLasPasadasVoladasNoLasBandasSaltadas() {
        // El mismo hueco de paridad de la rama X, en la rama Z: una banda ya vista por el medio.
        SweepArea area = SweepArea.ofChunks(0, 0, 23, 63);
        Coverage vista = coberturaDe(SweepArea.ofChunks(4, 0, 7, 63));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 4);

        assertEquals(5, plan.lanes().size());
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

    @Test
    void elMotivoDelRechazoNombraLosAjustesComoAparecenEnLaInterfaz() {
        // Nombrar el ajuste solo en prosa -"la anchura de pasada"- manda al jugador a buscar en la
        // ClickGUI algo que no existe con ese nombre. Los identificadores que fija este test son los
        // de los ajustes del módulo sweep/NetherSweep, y tienen que moverse juntos.
        SweepArea area = SweepArea.ofChunks(0, 0, 15, 15);

        String motivoCero = SweepPlanner.plan(area, Coverage.empty(), 0).rejection();
        assertTrue(motivoCero.contains("lane-width"), motivoCero);
        assertTrue(motivoCero.contains("lane-width-margin"), motivoCero);

        String motivoNegativo = SweepPlanner.plan(area, Coverage.empty(), -3).rejection();
        assertTrue(motivoNegativo.contains("lane-width"), motivoNegativo);
        assertTrue(motivoNegativo.contains("lane-width-margin"), motivoNegativo);
    }

    // ---------------------------------------------------------------------------------------
    // El plan como valor
    // ---------------------------------------------------------------------------------------

    @Test
    void totalBlocksCuentaLosEnlacesEntrePasadasYNoSoloLasPasadas() {
        // De este número sale la estimación de cohetes que se enseña ANTES de despegar (spec §6):
        // si sale por debajo de la distancia real, el módulo dice "te llegan" a quien no le llegan.
        // El caso lleva DOS bandas saltadas seguidas a propósito, para que uno de los enlaces sea
        // largo: un caso sin bandas saltadas pasaría igual aunque los enlaces no se contaran.
        //
        // Área 0..31 x 0..23 chunks, más ancha que alta: pasadas en X, apiladas en Z. Con anchura 4
        // salen seis bandas en Z (0-3, 4-7, 8-11, 12-15, 16-19, 20-23) y las bandas 4-7 y 8-11 están
        // vistas enteras, así que se vuelan cuatro pasadas.
        //
        // Centros de banda en Z, en bloques:  banda 0-3 -> 32;  12-15 -> 224;  16-19 -> 288;
        // 20-23 -> 352. El eje X va del centro del chunk 0 (bloque 8) al del chunk 31 (bloque 504),
        // así que cada pasada mide 496 bloques.
        //
        //   4 pasadas x 496                                              = 1984
        //   enlace tras saltarse dos bandas:      |224 - 32|             =  192
        //   enlace entre bandas seguidas:         |288 - 224|            =   64
        //   enlace entre bandas seguidas:         |352 - 288|            =   64
        //                                                          total = 2304
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 23);
        Coverage vista = coberturaDe(SweepArea.ofChunks(0, 4, 31, 11));

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, vista, 4);

        assertEquals(4, plan.lanes().size());
        assertEquals(2304.0, plan.totalBlocks(), 1e-9);

        double soloLasPasadas = 0.0;
        for (Lane pasada : plan.lanes()) {
            soloLasPasadas += pasada.lengthInBlocks();
        }
        assertEquals(1984.0, soloLasPasadas, 1e-9);
        assertTrue(plan.totalBlocks() > soloLasPasadas,
            "totalBlocks se quedó en la suma de las pasadas: la estimación de cohetes saldría corta");
    }

    @Test
    void totalBlocksDeUnaSolaPasadaEsEsaPasadaPorqueNoHayEnlace() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 3);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 4);

        assertEquals(1, plan.lanes().size());
        assertEquals(plan.lanes().get(0).lengthInBlocks(), plan.totalBlocks(), 1e-9);
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
     *
     * <p>Mira el eje largo de cada pasada, no siempre X. Comparando {@code toX} con {@code fromX} a
     * pelo, este helper solo dice algo de las áreas más anchas que altas: en las más altas que
     * anchas las pasadas corren en Z y sus X son centros de banda distintos, así que la afirmación
     * se volvía trivialmente falsa -o, si se hubiera aflojado, trivialmente cierta- y dejaba la
     * rama Z de la alternancia sin proteger.
     */
    private static void assertLasPasadasEncadenan(List<Lane> pasadas) {
        for (int i = 0; i + 1 < pasadas.size(); i++) {
            Lane actual = pasadas.get(i);
            Lane siguiente = pasadas.get(i + 1);
            assertTrue(actual.fromX() != actual.toX() || actual.fromZ() != actual.toZ(),
                "la pasada " + i + " no va a ninguna parte: " + actual);
            boolean enX = actual.fromZ() == actual.toZ();
            String eje = enX ? "X" : "Z";
            double finDeLaActual = enX ? actual.toX() : actual.toZ();
            double arranqueDeLaSiguiente = enX ? siguiente.fromX() : siguiente.fromZ();
            assertEquals(finDeLaActual, arranqueDeLaSiguiente, 1e-9,
                "la pasada " + (i + 1) + " no arranca en " + eje + " donde terminó la " + i
                    + ": el jugador recorre el largo del área en vacío entre las dos");
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
