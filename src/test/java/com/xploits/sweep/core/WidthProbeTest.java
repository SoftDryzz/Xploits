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

    @Test
    void laDistanciaEsDeChebyshevNoEuclidea() {
        // Un chunk en diagonal a (3,3) del jugador mide radio 3 -el lado del cuadrado-, no
        // sqrt(3*3+3*3) = 4,24. Medirlo en euclídea daría un radio mayor que el real en las
        // diagonales y abriría huecos justo entre pasadas, que es el fallo que este test protege.
        WidthProbe sonda = llenarHastaElMinimo();

        sonda.sample(new ChunkPos(100, 200), new ChunkPos(103, 203));

        assertEquals(3, sonda.observedRadiusInChunks());
    }

    @Test
    void unaDiagonalNoSimetricaSigueSiendoElMayorDeLosDosEjes() {
        // dx=2, dz=6: Chebyshev da 6, no la media ni la euclídea (6,32).
        WidthProbe sonda = llenarHastaElMinimo();

        sonda.sample(new ChunkPos(-10, -10), new ChunkPos(-8, -16));

        assertEquals(6, sonda.observedRadiusInChunks());
    }

    @Test
    void elResultadoEsElMaximoObservadoNoLaMedia() {
        // Muestras {2, 8, 3}: una ráfaga lenta que trae chunks cercanos no puede estrechar la
        // anchura ya vista. El resultado tiene que ser 8, no la media (13/3).
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(2, 0));
        }
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(8, 0));
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(3, 0));

        assertEquals(8, sonda.observedRadiusInChunks());
    }

    @Test
    void unaMuestraPosteriorMasPequenaNoBajaElMaximo() {
        WidthProbe sonda = llenarHastaElMinimo();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(20, 0));
        int maximoTrasLaGrande = sonda.observedRadiusInChunks();

        sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0));

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
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(5, 5));
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
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(4, 4));

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
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(4, 4));

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
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(1, 0));
        }
        return sonda;
    }

    /** Una sonda con bastantes muestras y radio observado exactamente {@code radio}. */
    private static WidthProbe sondaConRadio(int radio) {
        WidthProbe sonda = llenarHastaElMinimo();
        sonda.sample(new ChunkPos(0, 0), new ChunkPos(radio, 0));
        return sonda;
    }
}
