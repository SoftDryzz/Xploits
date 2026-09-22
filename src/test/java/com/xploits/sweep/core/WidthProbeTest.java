package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la medición de la anchura real del flujo de chunks (spec Nether Sweep §5 y §10).
 *
 * <p>Todas las coordenadas de estos tests son inventadas y pequeñas: lo que se comprueba es
 * aritmética de distancias, no ningún sitio concreto del mundo.
 */
class WidthProbeTest {
    /**
     * Un techo lo bastante alto como para que no descarte ninguna muestra de los tests que miden
     * otra cosa: 32 chunks es la distancia de renderizado maxima de Minecraft, asi que ninguna
     * muestra legitima lo pasa. Los tests del techo usan el suyo propio, a proposito.
     */
    private static final int TECHO = 32;

    /**
     * El jugador parado, que es la única situación en la que una muestra mide el alcance del
     * servidor y no la deriva de la cola. Lo usan los tests que comprueban otra cosa, para no
     * arrastrar el descarte por movimiento; los del descarte traen su propia velocidad.
     */
    private static final double QUIETO = 0;

    /**
     * Una elytra empujada por cohetes: unos 33 bloques por segundo, 1,65 por tick. Es la velocidad a
     * la que se vuela un barrido entero y a la que toda muestra viene inflada por la deriva.
     */
    private static final double VOLANDO = 1.65;

    @Test
    void laDistanciaEsDeChebyshevNoEuclidea() {
        // Un chunk en diagonal a (3,3) del jugador mide radio 3 -el lado del cuadrado-, no
        // sqrt(3*3+3*3) = 4,24. Medirlo en euclídea daría un radio mayor que el real en las
        // diagonales y abriría huecos justo entre pasadas, que es el fallo que este test protege.
        WidthProbe sonda = llenarHastaElMinimo();

        sonda.sample(new ChunkPos(100, 200), new ChunkPos(103, 203), TECHO, QUIETO);

        assertEquals(3, sonda.observedRadiusInChunks());
    }

    @Test
    void unaDiagonalNoSimetricaSigueSiendoElMayorDeLosDosEjes() {
        // dx=2, dz=6: Chebyshev da 6, no la media ni la euclídea (6,32).
        WidthProbe sonda = llenarHastaElMinimo();

        sonda.sample(new ChunkPos(-10, -10), new ChunkPos(-8, -16), TECHO, QUIETO);

        assertEquals(6, sonda.observedRadiusInChunks());
    }

    @Test
    void elResultadoEsElMaximoObservadoNoLaMedia() {
        // Muestras {2, 8, 3}: una ráfaga lenta que trae chunks cercanos no puede estrechar la
        // anchura ya vista. El resultado tiene que ser 8, no la media (13/3).
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(2, 0), TECHO, QUIETO);
        }
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), TECHO, QUIETO);
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(3, 0), TECHO, QUIETO);

        assertEquals(8, sonda.observedRadiusInChunks());
    }

    @Test
    void unaMuestraPosteriorMasPequenaNoBajaElMaximo() {
        WidthProbe sonda = llenarHastaElMinimo();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(20, 0), TECHO, QUIETO);
        int maximoTrasLaGrande = sonda.observedRadiusInChunks();

        sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), TECHO, QUIETO);

        assertEquals(maximoTrasLaGrande, sonda.observedRadiusInChunks());
    }

    // ---------------------------------------------------------------------------------------
    // hasEnoughSamples(): no se planifica con un dato que aún no existe
    // ---------------------------------------------------------------------------------------

    @Test
    void sinMuestrasNoHayBastantes() {
        WidthProbe sonda = new WidthProbe();

        assertFalse(sonda.hasEnoughSamples());
    }

    @Test
    void conPocasMuestrasNoHayBastantes() {
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS - 1; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(5, 5), TECHO, QUIETO);
        }

        assertFalse(sonda.hasEnoughSamples());
    }

    @Test
    void conElMinimoExactoDeMuestrasYaHayBastantes() {
        WidthProbe sonda = llenarHastaElMinimo();

        assertTrue(sonda.hasEnoughSamples());
    }

    @Test
    void observedRadiusInChunksLanzaSiTodaviaNoHayBastantesMuestras() {
        WidthProbe sonda = new WidthProbe();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(4, 4), TECHO, QUIETO);

        assertThrows(IllegalStateException.class, sonda::observedRadiusInChunks);
    }

    // ---------------------------------------------------------------------------------------
    // laneWidthInChunks(): radio observado -> anchura de pasada segura (spec §5, margen)
    // ---------------------------------------------------------------------------------------

    @Test
    void laAnchuraEsElDobleDelRadioSeguroRedondeadoHaciaAbajo() {
        // Radio 7, margen 0.1: radio seguro 6.3, anchura 12.6. Tiene que salir 12, no 13 -el
        // redondeo va siempre hacia la anchura más estrecha, nunca hacia la más ancha.
        WidthProbe sonda = sondaConRadio(7);

        assertEquals(12, sonda.laneWidthInChunks(0.1));
    }

    @Test
    void margenCeroDaElDobleExactoDelRadioObservado() {
        WidthProbe sonda = sondaConRadio(7);

        assertEquals(14, sonda.laneWidthInChunks(0.0));
    }

    @Test
    void unMargenMayorReduceMasLaAnchura() {
        WidthProbe sonda = sondaConRadio(10);

        int conMargenPequeno = sonda.laneWidthInChunks(0.1);
        int conMargenGrande = sonda.laneWidthInChunks(0.4);

        assertTrue(conMargenGrande < conMargenPequeno,
            "un margen mayor tiene que dar una anchura igual o más estrecha, nunca más ancha");
    }

    @Test
    void laneWidthInChunksLanzaSiTodaviaNoHayBastantesMuestras() {
        WidthProbe sonda = new WidthProbe();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(4, 4), TECHO, QUIETO);

        assertThrows(IllegalStateException.class, () -> sonda.laneWidthInChunks(0.2));
    }

    @Test
    void unMargenNegativoLanza() {
        WidthProbe sonda = sondaConRadio(7);

        assertThrows(IllegalArgumentException.class, () -> sonda.laneWidthInChunks(-0.1));
    }

    @Test
    void unMargenDeUnoOMasLanza() {
        WidthProbe sonda = sondaConRadio(7);

        assertThrows(IllegalArgumentException.class, () -> sonda.laneWidthInChunks(1.0));
    }

    /** Una sonda con exactamente {@link WidthProbe#MUESTRAS_MINIMAS} muestras de radio 1. */
    private static WidthProbe llenarHastaElMinimo() {
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), TECHO, QUIETO);
        }
        return sonda;
    }

    /** Una sonda con bastantes muestras y radio observado exactamente {@code radio}. */
    private static WidthProbe sondaConRadio(int radio) {
        WidthProbe sonda = llenarHastaElMinimo();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(radio, 0), TECHO, QUIETO);
        return sonda;
    }

    // ---------------------------------------------------------------------------------------
    // El techo: una muestra por encima de lo que el cliente declaro es un artefacto
    // ---------------------------------------------------------------------------------------

    @Test
    void unaMuestraPorEncimaDelTechoNiFijaElMaximoNiCuentaComoMuestra() {
        // El vuelo del fallo: el servidor encola un lote cuando el jugador esta en un sitio y se lo
        // entrega diez chunks mas alla, asi que esa muestra mide radio real + 10. Con techo 8 no
        // puede venir del servidor, asi que no es una medida de nada.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 8, QUIETO);
        }

        sonda.sample(new ChunkPos(0, 0), new ChunkPos(18, 0), 8, QUIETO);

        assertEquals(8, sonda.observedRadiusInChunks());
        assertEquals(WidthProbe.MUESTRAS_MINIMAS, sonda.sampleCount());
        assertEquals(1, sonda.discardedSamples());
    }

    @Test
    void elChunkTardioNoEnsanchaLaPasadaParaElRestoDeLaSesion() {
        // La consecuencia medida en chunks de pasada, que es lo que de verdad abre los huecos: con
        // el artefacto dentro, laneWidthInChunks daria 27 sobre un servidor que cubre 16.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 8, QUIETO);
        }
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(18, 0), 8, QUIETO);

        assertEquals(12, sonda.laneWidthInChunks(0.25));
    }

    @Test
    void unaMuestraJustoEnElTechoSiCuenta() {
        // El servidor manda un cuadrado de lado 2r+1: un chunk a distancia exactamente r es el borde
        // legitimo, no un artefacto. Descartarlo estrecharia la pasada sin motivo.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(16, 0), 16, QUIETO);
        }

        assertEquals(16, sonda.observedRadiusInChunks());
        assertEquals(0, sonda.discardedSamples());
    }

    @Test
    void elTechoSeAplicaTambienEnDiagonal() {
        // La distancia es de Chebyshev, asi que un chunk a (9,9) con techo 8 se pasa por los dos
        // ejes a la vez y se descarta igual.
        WidthProbe sonda = new WidthProbe();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(9, 9), 8, QUIETO);

        assertEquals(0, sonda.sampleCount());
        assertEquals(1, sonda.discardedSamples());
        assertFalse(sonda.hasEnoughSamples());
    }

    @Test
    void soloArtefactosNoEsUnaSondaConMedida() {
        // Si todo lo que llega se descarta, la sonda tiene que seguir diciendo que no sabe, no
        // quedarse con un radio 0 con pinta de medida.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS * 2; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(30, 0), 8, QUIETO);
        }

        assertFalse(sonda.hasEnoughSamples());
        assertThrows(IllegalStateException.class, sonda::observedRadiusInChunks);
    }

    @Test
    void unTechoQueDescartariaTodoSeRechazaEnVezDeDejarLaSondaMuda() {
        WidthProbe sonda = new WidthProbe();

        assertThrows(IllegalArgumentException.class,
            () -> sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), 0, QUIETO));
        assertThrows(IllegalArgumentException.class,
            () -> sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), -4, QUIETO));
    }

    // ---------------------------------------------------------------------------------------
    // El movimiento: una muestra tomada volando mide la cola del servidor, no su alcance
    // ---------------------------------------------------------------------------------------

    @Test
    void unaMuestraTomadaEnMovimientoNiFijaElMaximoNiCuentaComoMuestra() {
        WidthProbe sonda = new WidthProbe();

        sonda.sample(new ChunkPos(0, 0), new ChunkPos(14, 0), 16, VOLANDO);

        assertEquals(0, sonda.sampleCount());
        assertEquals(1, sonda.movingSamples());
        assertEquals(0, sonda.discardedSamples(), "el descarte por movimiento no es el del techo");
        assertFalse(sonda.hasEnoughSamples());
    }

    @Test
    void laMedidaNoSubeCuandoElServidorCubreMenos() {
        // El fallo entero, con los números del javadoc de la clase: techo 16, un servidor que de
        // verdad entrega 8, y el jugador volando con elytra. Cada muestra llega inflada por la
        // deriva de la cola -14, 15, 16 chunks- y ninguna se pasa del techo, así que antes de este
        // arreglo todas contaban y el máximo acababa pegado a 16 justo cuando el alcance real
        // bajaba. Lo que se mide tiene que ser lo que el servidor cubre, no lo que el jugador se
        // movió mientras el paquete estaba en cola.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 16, QUIETO);
        }
        for (int i = 0; i < 20; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(14 + i % 3, 0), 16, VOLANDO);
        }

        assertEquals(8, sonda.observedRadiusInChunks());
        assertEquals(12, sonda.laneWidthInChunks(0.2),
            "media banda tiene que caber en los 8 chunks que el servidor cubre de verdad");
        assertEquals(20, sonda.movingSamples());
    }

    @Test
    void andarNoEsVolarYSusMuestrasCuentan() {
        // Correr son 5,6 bloques por segundo, 0,28 por tick: por debajo del tope, y con esa
        // velocidad ni un segundo entero de cola infla la muestra un chunk. Si esto no contara, la
        // única forma de medir sería quedarse absolutamente quieto, y quieto el servidor no manda
        // chunks nuevos: la sonda no llegaría a medir nunca.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(7, 0), 16, 0.28);
        }

        assertTrue(sonda.hasEnoughSamples());
        assertEquals(7, sonda.observedRadiusInChunks());
        assertEquals(0, sonda.movingSamples());
    }

    @Test
    void unaMuestraJustoEnElTopeDeVelocidadSiCuenta() {
        WidthProbe sonda = new WidthProbe();

        sonda.sample(new ChunkPos(0, 0), new ChunkPos(5, 0), 16, WidthProbe.BLOQUES_POR_TICK_MAXIMOS);

        assertEquals(1, sonda.sampleCount());
        assertEquals(0, sonda.movingSamples());
    }

    @Test
    void unaVelocidadQueNoEsUnaVelocidadSeRechazaEnVezDeColarLaMuestra() {
        WidthProbe sonda = new WidthProbe();

        assertThrows(IllegalArgumentException.class,
            () -> sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), 16, -1));
        assertThrows(IllegalArgumentException.class,
            () -> sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0), 16, Double.NaN));
        assertEquals(0, sonda.sampleCount());
    }

    @Test
    void elTechoPuedeCambiarAMitadDeSesionYCadaMuestraSeJuzgaConElSuyo() {
        // El jugador baja su distancia de renderizado, o el servidor declara otra: la muestra que
        // era legitima con el techo viejo se descarta con el nuevo.
        WidthProbe sonda = new WidthProbe();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(12, 0), 16, QUIETO);
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(12, 0), 8, QUIETO);

        assertEquals(1, sonda.sampleCount());
        assertEquals(1, sonda.discardedSamples());
    }
}
