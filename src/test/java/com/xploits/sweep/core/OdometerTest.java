package com.xploits.sweep.core;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del cuentakilómetros del vuelo: que un teletransporte no se cuente como vuelo y no infle la
 * tasa de bloques por cohete (spec Nether Sweep §6).
 *
 * <p>Todas las distancias de estos tests son inventadas: lo que se comprueba es aritmética, no
 * ningún sitio concreto del mundo.
 */
class OdometerTest {
    @Test
    void sinNadaRegistradoNoSeHaVoladoNada() {
        Odometer cuenta = new Odometer();

        assertEquals(0, cuenta.blocksFlown());
        assertEquals(0, cuenta.jumps());
    }

    @Test
    void losPasosNormalesSeSuman() {
        Odometer cuenta = new Odometer();

        assertTrue(cuenta.advance(1.6));
        assertTrue(cuenta.advance(1.7));

        assertEquals(3.3, cuenta.blocksFlown(), 1e-9);
        assertEquals(0, cuenta.jumps());
    }

    @Test
    void unSaltoNiSeSumaNiSeReparte() {
        Odometer cuenta = new Odometer();
        cuenta.advance(1.6);

        assertFalse(cuenta.advance(4_000));

        assertEquals(1.6, cuenta.blocksFlown(), 1e-9);
        assertEquals(1, cuenta.jumps());
    }

    @Test
    void unPasoJustoEnElTopeSigueSiendoVuelo() {
        Odometer cuenta = new Odometer();

        assertTrue(cuenta.advance(Odometer.BLOQUES_POR_TICK_MAXIMOS));

        assertEquals(Odometer.BLOQUES_POR_TICK_MAXIMOS, cuenta.blocksFlown(), 1e-9);
        assertEquals(0, cuenta.jumps());
    }

    @Test
    void elVueloMasRapidoQueSePuedeSostenerNoEsUnSalto() {
        // Elytra con cohetes encadenados en picado, unos 60 bloques por segundo: 3 por tick. Si esto
        // se descartara, el cuentakilómetros no contaría el vuelo de verdad y la tasa saldría al
        // revés -menos bloques por cohete de los reales-, que corta barridos que sí llegaban.
        Odometer cuenta = new Odometer();

        assertTrue(cuenta.advance(3));

        assertEquals(3, cuenta.blocksFlown(), 1e-9);
    }

    @Test
    void elUltimoPasoSeEntregaEnBrutoAunqueSeaUnSalto() {
        // La sonda de anchura lo usa como velocidad del jugador: un tick con un teletransporte
        // dentro es el peor momento para medir el alcance del servidor, así que tiene que verlo
        // grande y descartar la muestra, no verlo filtrado a cero.
        Odometer cuenta = new Odometer();

        cuenta.advance(4_000);

        assertEquals(4_000, cuenta.lastStep(), 1e-9);
    }

    @Test
    void unaDistanciaQueNoEsUnaDistanciaSeRechaza() {
        Odometer cuenta = new Odometer();

        assertThrows(IllegalArgumentException.class, () -> cuenta.advance(-1));
        assertThrows(IllegalArgumentException.class, () -> cuenta.advance(Double.NaN));
        assertEquals(0, cuenta.blocksFlown());
    }

    @Test
    void unTeletransporteNoInflaLaTasaDeBloquesPorCohete() {
        // El fallo completo, hasta la decisión que dependía de él: el jugador vuela 1.000 bloques
        // gastando 10 cohetes -100 bloques por cohete-, cruza un portal que le mueve 4.000, y vuela
        // otros 1.000 gastando otros 10. Sumando el salto, la tasa saldría de 6.000 bloques entre
        // 20 cohetes: 300 por cohete, el triple de la real, y con ella willRunOut contesta que los
        // cohetes llegan cuando no llegan.
        Odometer cuenta = new Odometer();
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(cuenta.blocksFlown(), 100);

        for (int i = 0; i < 625; i++) cuenta.advance(1.6);
        presupuesto.sample(cuenta.blocksFlown(), 90);
        cuenta.advance(4_000);
        for (int i = 0; i < 625; i++) cuenta.advance(1.6);
        presupuesto.sample(cuenta.blocksFlown(), 80);

        OptionalDouble tasa = presupuesto.blocksPerRocket();
        assertTrue(tasa.isPresent());
        assertEquals(100, tasa.getAsDouble(), 1e-9);
        // Y la decisión que cuelga de ella: con 80 cohetes a 100 bloques cada uno quedan 8.000
        // bloques de autonomía, así que 9.000 por delante con un 20 % de reserva no llegan.
        assertTrue(presupuesto.willRunOut(9_000, 80, 0.2),
            "con la tasa real la proyección corta; con la inflada por el salto diría que llegan");
    }
}
