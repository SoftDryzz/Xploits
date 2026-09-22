package com.xploits.sweep.core;

import com.xploits.travel.core.RoutePlanner;
import com.xploits.travel.core.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepRouteTest {
    private static final double EPSILON = 1e-9;

    /**
     * Dos pasadas de 100 bloques separadas por 50, en sentidos alternos como las emite el
     * planificador. Los vértices salen (0,0) (100,0) (100,50) (0,50), así que los tres huecos miden
     * 100, 50 y 100: el barrido son 250 bloques calculados a mano, no con la fórmula de la clase.
     */
    private static List<Lane> dosPasadas() {
        return List.of(new Lane(0, 0, 100, 0), new Lane(100, 50, 0, 50));
    }

    // ---------------------------------------------------------------------------------------
    // Los vértices
    // ---------------------------------------------------------------------------------------

    @Test
    void cadaPasadaAportaSuPrincipioYSuFinalEnEseOrden() {
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertEquals(4, ruta.size());
        assertEquals(List.of(
            new Waypoint(0, 0),
            new Waypoint(100, 0),
            new Waypoint(100, 50),
            new Waypoint(0, 50)), ruta.waypoints());
    }

    // ---------------------------------------------------------------------------------------
    // Las tres patas del viaje
    // ---------------------------------------------------------------------------------------

    @Test
    void lasTresPatasSalenCadaUnaConSuNumero() {
        // Despegando en (0,-30): 30 bloques hasta el arranque de la primera pasada, 250 de barrido
        // (100 + 50 + 100) y 80 de vuelta desde (0,50) hasta (0,-30). Total 360.
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertEquals(30, ruta.approachBlocks(), EPSILON);
        assertEquals(250, ruta.sweepBlocks(), EPSILON);
        assertEquals(80, ruta.returnBlocks(), EPSILON);
        assertEquals(360, ruta.totalBlocks(), EPSILON);
    }

    @Test
    void sinContarElRegresoElViajeEsSoloAproximacionYBarrido() {
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), false);

        assertEquals(0, ruta.returnBlocks(), EPSILON);
        assertEquals(280, ruta.totalBlocks(), EPSILON);
        assertEquals(250, ruta.sweepBlocks(), EPSILON);
    }

    @Test
    void laAproximacionEsEuclideaNoPorEjes() {
        // Despegando en (-30,-40), el arranque de la primera pasada está en (0,0): 50 bloques por el
        // triángulo 3-4-5, no los 70 que darían si se sumaran los dos ejes.
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(-30, -40), false);

        assertEquals(50, ruta.approachBlocks(), EPSILON);
    }

    /**
     * La regresión del fallo que se coló dos veces: presupuestar el viaje con el «total» del plan.
     * Ese número es solo el barrido, y la diferencia con el viaje de verdad son exactamente la
     * aproximación y la vuelta.
     */
    @Test
    void elViajeEsEstrictamenteMayorQueElTotalDelPlanYLaDiferenciaEsLaAproximacionMasElRegreso() {
        SweepPlanner.SweepPlan plan = SweepPlanner.SweepPlan.of(dosPasadas());
        SweepRoute ruta = SweepRoute.of(plan.lanes(), new Waypoint(0, -30), true);

        assertEquals(250, plan.totalBlocks(), EPSILON);
        assertTrue(ruta.totalBlocks() > plan.totalBlocks());
        assertEquals(110, ruta.totalBlocks() - plan.totalBlocks(), EPSILON);
    }

    /**
     * Los dos caminos al mismo número, sobre un plan de verdad del planificador: {@code
     * SweepPlan.totalBlocks()} suma longitudes de pasada y saltos entre ellas, y {@code sweepBlocks()}
     * suma distancias entre vértices consecutivos. Si alguna vez dejan de coincidir es que uno de los
     * dos se ha roto.
     */
    @Test
    void elBarridoDeLaRutaCoincideConElTotalDelPlanDelPlanificador() {
        SweepArea area = SweepArea.ofChunks(0, 0, 31, 23);
        SweepPlanner.SweepPlan plan = SweepPlanner.plan(area, Coverage.empty(), 8);
        assertTrue(plan.lanes().size() > 1, "el caso solo vale si hay enlaces entre pasadas");

        SweepRoute ruta = SweepRoute.of(plan.lanes(), new Waypoint(-5_000, -5_000), true);

        assertEquals(plan.totalBlocks(), ruta.sweepBlocks(), EPSILON);
    }

    // ---------------------------------------------------------------------------------------
    // Lo que queda por volar: es lo que alimenta la proyección de cohetes
    // ---------------------------------------------------------------------------------------

    @Test
    void loQueQuedaDesdeCadaVerticeSeCuentaHaciaAtrasConElRegresoDentro() {
        // Desde (0,50), el último vértice, solo queda la vuelta: 80. Desde (100,50), 100 + 80.
        // Desde (100,0), 50 + 100 + 80. Desde (0,0), 100 + 50 + 100 + 80.
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertEquals(330, ruta.remainingFrom(0), EPSILON);
        assertEquals(230, ruta.remainingFrom(1), EPSILON);
        assertEquals(180, ruta.remainingFrom(2), EPSILON);
        assertEquals(80, ruta.remainingFrom(3), EPSILON);
    }

    @Test
    void sinRegresoElUltimoVerticeNoDejaNadaPorVolar() {
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), false);

        assertEquals(250, ruta.remainingFrom(0), EPSILON);
        assertEquals(0, ruta.remainingFrom(3), EPSILON);
    }

    @Test
    void loQueLeQuedaAlJugadorSumaLoQueHayDesdeDondeEstaHastaElVerticeAlQueVa() {
        // A mitad del enlace entre las dos pasadas, en (100,20): 30 hasta (100,50) y desde ahí 180.
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertEquals(210, ruta.remainingFrom(2, new Waypoint(100, 20)), EPSILON);
    }

    @Test
    void unVerticeQueNoExisteNoDevuelveUnaDistanciaInventada() {
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertThrows(IndexOutOfBoundsException.class, () -> ruta.remainingFrom(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> ruta.remainingFrom(4));
        assertThrows(IndexOutOfBoundsException.class, () -> ruta.remainingFrom(4, new Waypoint(0, 0)));
    }

    // ---------------------------------------------------------------------------------------
    // El hueco más corto: de él depende que ninguna pasada se consuma sin volarse
    // ---------------------------------------------------------------------------------------

    @Test
    void elHuecoMasCortoEsElEnlaceEntrePasadasCuandoEsMasCortoQueLasPasadas() {
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertEquals(50, ruta.tightestGap(), EPSILON);
    }

    @Test
    void elHuecoMasCortoPuedeSerUnaPasadaSiLasPasadasSonMasCortasQueElEnlace() {
        // Pasadas de 40 separadas por 300: el hueco corto es la pasada, no el enlace.
        List<Lane> pasadasCortas = List.of(new Lane(0, 0, 40, 0), new Lane(40, 300, 0, 300));
        SweepRoute ruta = SweepRoute.of(pasadasCortas, new Waypoint(0, 0), false);

        assertEquals(40, ruta.tightestGap(), EPSILON);
    }

    @Test
    void unaRutaDeUnaSolaPasadaTieneUnUnicoHuecoQueEsLaPropiaPasada() {
        SweepRoute ruta = SweepRoute.of(List.of(new Lane(0, 0, 400, 0)), new Waypoint(0, 0), true);

        assertEquals(2, ruta.size());
        assertEquals(0, ruta.approachBlocks(), EPSILON);
        assertEquals(400, ruta.sweepBlocks(), EPSILON);
        assertEquals(400, ruta.returnBlocks(), EPSILON);
        assertEquals(800, ruta.totalBlocks(), EPSILON);
        assertEquals(400, ruta.tightestGap(), EPSILON);
    }

    // ---------------------------------------------------------------------------------------
    // Lo que no se construye
    // ---------------------------------------------------------------------------------------

    @Test
    void unPlanSinPasadasNoEsUnaRutaCorta() {
        // Un plan aceptado sin pasadas significa que el área ya está vista entera. Devolver una ruta
        // vacía dejaría que alguien despegara hacia ninguna parte.
        assertThrows(IllegalArgumentException.class,
            () -> SweepRoute.of(List.of(), new Waypoint(0, 0), true));
    }

    @Test
    void sinSaberDeDondeSeDespegaNoHayViajeQuePresupuestar() {
        assertThrows(NullPointerException.class, () -> SweepRoute.of(dosPasadas(), null, true));
        assertThrows(NullPointerException.class, () -> SweepRoute.of(null, new Waypoint(0, 0), true));
    }

    // ---------------------------------------------------------------------------------------
    // minimumGap(): la regla de separacion del barrido, que no es la de las rutas de evasion
    // ---------------------------------------------------------------------------------------

    @Test
    void laSeparacionMinimaDeUnBarridoEsElMargenYNoElDoble() {
        // En un barrido los vertices forman angulo recto: al que remata una pasada se llega a lo
        // largo de ella y el siguiente esta perpendicular, asi que la distancia al segundo es la
        // hipotenusa y nunca baja del hueco. El doble del margen protege de dos vertices alineados,
        // que es la geometria de las rutas de evasion, no la de un cortacesped.
        assertEquals(150, SweepRoute.minimumGap(150), EPSILON);
        assertEquals(100, SweepRoute.minimumGap(100), EPSILON);
    }

    @Test
    void laPasadaMasCortaYElEnlaceMasCortoSonHuecosDistintos() {
        // Los vertices salen en parejas: el hueco 0->1 es una pasada, el 1->2 el enlace a la
        // siguiente. Con dosPasadas() las pasadas miden 100 y el enlace 50.
        SweepRoute ruta = SweepRoute.of(dosPasadas(), new Waypoint(0, -30), true);

        assertEquals(100, ruta.shortestLane(), EPSILON);
        assertEquals(50, ruta.shortestLink(), EPSILON);
        assertEquals(50, ruta.tightestGap(), EPSILON);
    }

    @Test
    void unaRutaDeUnaSolaPasadaNoTieneNingunEnlace() {
        // Y entonces el enlace mas corto no es cero -que pareceria un enlace imposible- sino que no
        // hay ninguno: quien avise sobre enlaces cortos no tiene nada de que avisar.
        SweepRoute ruta = SweepRoute.of(List.of(new Lane(0, 0, 400, 0)), new Waypoint(0, 0), false);

        assertEquals(400, ruta.shortestLane(), EPSILON);
        assertEquals(Double.MAX_VALUE, ruta.shortestLink(), EPSILON);
    }

    @Test
    void unServidorQueEntregaOchoChunksSePuedeBarrerConLosValoresDeFabrica() {
        // El fallo entero, con numeros: radio observado 8 -normal en un anarchy cargado- y el margen
        // de anchura de fabrica dan una anchura de 12 chunks, o sea enlaces entre pasadas de menos
        // de 300 bloques. Contra el suelo de las rutas de evasion ESE barrido se rechazaba siempre,
        // para cualquier rectangulo, y ninguna de las salidas que el rechazo ofrecia funcionaba.
        WidthProbe sonda = new WidthProbe();
        for (int i = 0; i < WidthProbe.MUESTRAS_MINIMAS; i++) {
            sonda.sample(new ChunkPos(0, 0), new ChunkPos(8, 0), 16, 0);
        }
        int anchura = sonda.laneWidthInChunks(0.2);
        assertEquals(12, anchura);

        SweepPlanner.SweepPlan plan = SweepPlanner.plan(new SweepArea(0, 0, 199, 199),
            Coverage.empty(), anchura);
        SweepRoute ruta = SweepRoute.of(plan.lanes(), new Waypoint(0, 0), true);

        assertTrue(ruta.shortestLane() > SweepRoute.minimumGap(RoutePlanner.DEFAULT_WAYPOINT_MARGIN),
            "ninguna pasada se pierde, que es lo unico que no se puede permitir");
        assertTrue(ruta.tightestGap() < RoutePlanner.minimumSpacing(RoutePlanner.DEFAULT_WAYPOINT_MARGIN),
            "y el hueco mas corto sigue por debajo del suelo de las rutas de evasion: ese es el caso"
                + " que se rechazaba siempre");
    }

    @Test
    void laUltimaBandaEstrechaJuntaSuPasadaALaAnteriorSinPerderNinguna() {
        // Un area que no es multiplo de la anchura deja una ultima banda mas estrecha, su pasada se
        // centra mas cerca de la anterior y el enlace se queda en poco mas de media anchura. Eso es
        // un enlace corto -esquina perdida, aviso-, nunca una pasada perdida.
        SweepPlanner.SweepPlan plan = SweepPlanner.plan(new SweepArea(0, 0, 199, 192),
            Coverage.empty(), 12);
        SweepRoute ruta = SweepRoute.of(plan.lanes(), new Waypoint(0, 0), false);

        assertEquals(104, ruta.shortestLink(), EPSILON);
        assertTrue(ruta.shortestLane() > SweepRoute.minimumGap(RoutePlanner.DEFAULT_WAYPOINT_MARGIN));
    }

    @Test
    void unAreaDiminutaPorSuLadoLargoSiSeQuedaSinPasadaVolable() {
        // Esto es lo que si hay que rechazar: un area de 8 chunks de lado largo da pasadas de 112
        // bloques, que caben dentro del margen de fabrica. Esa pasada se consumiria sin volarla y el
        // barrido la daria por peinada igual, que es la mentira de spec 9.
        SweepPlanner.SweepPlan plan = SweepPlanner.plan(new SweepArea(0, 0, 7, 7), Coverage.empty(), 12);
        SweepRoute ruta = SweepRoute.of(plan.lanes(), new Waypoint(0, 0), false);

        assertEquals(112, ruta.shortestLane(), EPSILON);
        assertTrue(ruta.shortestLane() <= SweepRoute.minimumGap(RoutePlanner.DEFAULT_WAYPOINT_MARGIN));
    }
}
