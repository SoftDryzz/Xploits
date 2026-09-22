package com.xploits.sweep.core;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la medición del gasto real de cohetes y su proyección (spec Nether Sweep §6 y §10).
 *
 * <p>Todos los bloques y cohetes de estos tests son inventados y pequeños: lo que se comprueba es
 * la aritmética del gasto, no ningún vuelo real.
 */
class FuelBudgetTest {

    // ---------------------------------------------------------------------------------------
    // blocksPerRocket()
    // ---------------------------------------------------------------------------------------

    @Test
    void sinNingunaMuestraBlocksPerRocketEstaVacio() {
        FuelBudget presupuesto = new FuelBudget();

        assertTrue(presupuesto.blocksPerRocket().isEmpty());
    }

    @Test
    void conUnaSolaMuestraBlocksPerRocketSigueVacio() {
        // Un solo punto no es una diferencia: no hay tramo del que sacar un gasto.
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 20);

        assertTrue(presupuesto.blocksPerRocket().isEmpty());
    }

    @Test
    void conMuestrasElGastoSaleDeBloquesRecorridosEntreCohetesGastados() {
        // Referencia en (0 bloques, 20 cohetes); tras volar 800 bloques quedan 16 -se han gastado
        // 4-, así que el gasto es 800/4 = 200 bloques por cohete.
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 20);
        presupuesto.sample(800.0, 16);

        OptionalDouble gasto = presupuesto.blocksPerRocket();

        assertTrue(gasto.isPresent());
        assertEquals(200.0, gasto.getAsDouble(), 1e-9);
    }

    @Test
    void elGastoSeAcumulaSobreVariosTramos() {
        // Dos tramos de gasto real: 400 bloques por 2 cohetes, luego 600 bloques por 4 cohetes.
        // Total: 1000 bloques / 6 cohetes.
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 20);
        presupuesto.sample(400.0, 18);
        presupuesto.sample(1000.0, 14);

        assertEquals(1000.0 / 6.0, presupuesto.blocksPerRocket().getAsDouble(), 1e-9);
    }

    @Test
    void reponerCohetesAMitadDeVueloNoProduceUnGastoNegativoNiRompeLaProyeccion() {
        // El jugador repone de 8 a 12 cohetes en el tramo intermedio: ese tramo se ignora entero
        // -ni sus bloques ni su variación de cohetes cuentan-, y el gasto final tiene que coincidir
        // con el que habría salido sin la reposición: 1000 bloques / 4 cohetes = 250.
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 10);
        presupuesto.sample(500.0, 8);   // gasta 2 cohetes en 500 bloques
        presupuesto.sample(600.0, 12);  // repone: sube de 8 a 12, tramo ignorado
        presupuesto.sample(1100.0, 10); // gasta 2 cohetes más en 500 bloques

        OptionalDouble gasto = presupuesto.blocksPerRocket();

        assertTrue(gasto.isPresent());
        assertTrue(gasto.getAsDouble() > 0, "el gasto no puede salir negativo tras una reposición");
        assertEquals(250.0, gasto.getAsDouble(), 1e-9);
    }

    @Test
    void unTramoDondeLosCohetesSeMantienenIgualesNoCuentaComoGasto() {
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 10);
        presupuesto.sample(300.0, 10); // vuela sin gastar cohetes -planeando, por ejemplo-

        assertTrue(presupuesto.blocksPerRocket().isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // El gasto medido caduca si pasa demasiado vuelo sin confirmarlo (reposición frecuente)
    // ---------------------------------------------------------------------------------------

    @Test
    void unGastoMedidoSeDaPorCaducadoTrasVolarSinConfirmarloTantoComoLoQueCostoConfirmarlo() {
        // Se confirma un gasto de 400 bloques / 2 cohetes = 200 bloques/cohete. Luego el jugador
        // repone a menudo -cada muestra siguiente sube o mantiene los cohetes-, así que ningún
        // tramo vuelve a confirmar gasto real. Sin caducidad, blocksPerRocket() se quedaría
        // congelado en 200 para siempre, aunque el consumo real de después fuera distinto.
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 10);
        presupuesto.sample(400.0, 8); // confirma 400/2 = 200

        assertEquals(200.0, presupuesto.blocksPerRocket().getAsDouble(), 1e-9,
            "el gasto tiene que estar disponible justo tras confirmarlo");

        presupuesto.sample(700.0, 9);  // repone: 8 -> 9, ignorado, 300 bloques sin confirmar
        presupuesto.sample(900.0, 9);  // se mantiene: 9 -> 9, ignorado, 500 bloques sin confirmar

        // 500 bloques sin confirmar superan los 400 que costó confirmar la tasa: caducada.
        assertTrue(presupuesto.blocksPerRocket().isEmpty(),
            "un gasto viejo sin confirmar no puede seguir usándose como si fuera actual");
    }

    @Test
    void exactamenteElMismoVueloQueCostoConfirmarElGastoTodaviaNoLoCaduca() {
        // Límite exacto: 400 bloques sin confirmar contra 400 que costó confirmar. Todavía cuenta
        // como vigente -la caducidad exige superarlo, no solo igualarlo-, y un bloque más lo pasa.
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 10);
        presupuesto.sample(400.0, 8); // confirma 400/2 = 200
        presupuesto.sample(800.0, 8); // se mantiene: 400 bloques sin confirmar, igual a lo confirmado

        assertTrue(presupuesto.blocksPerRocket().isPresent(),
            "en el límite exacto el dato todavía es de fiar");

        presupuesto.sample(801.0, 8); // un bloque más sin confirmar y ya supera el límite

        assertTrue(presupuesto.blocksPerRocket().isEmpty());
    }

    @Test
    void unTramoConGastoRealTrasLaCaducidadRestableceLaConfianzaDeInmediato() {
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 10);
        presupuesto.sample(400.0, 8); // confirma 400/2 = 200
        presupuesto.sample(900.0, 8); // 500 sin confirmar: caducado

        assertTrue(presupuesto.blocksPerRocket().isEmpty());

        presupuesto.sample(1100.0, 7); // gasta 1 cohete en 200 bloques: vuelve a confirmar

        assertTrue(presupuesto.blocksPerRocket().isPresent(),
            "un tramo con gasto real tiene que restablecer la confianza aunque el dato anterior"
                + " hubiera caducado");
    }

    @Test
    void willRunOutLanzaSiElGastoMedidoHaCaducado() {
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 10);
        presupuesto.sample(400.0, 8);
        presupuesto.sample(900.0, 8); // 500 sin confirmar: caducado

        assertThrows(java.util.NoSuchElementException.class,
            () -> presupuesto.willRunOut(1000.0, 10, 0.2));
    }

    // ---------------------------------------------------------------------------------------
    // willRunOut(): corta antes de llegar a cero, respetando la reserva
    // ---------------------------------------------------------------------------------------

    @Test
    void conReservaDelVeintePorCientoCortaConCohetesTodaviaEnLaMano() {
        // Gasto medido: 100 bloques por cohete. Quedan 5000 bloques -> hacen falta 50 cohetes sin
        // reserva; con un 20% de reserva el umbral sube a 60. Con 55 cohetes en mano -por encima de
        // los 50 que hacen falta a pelo- ya debe cortar, porque 55 < 60.
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        assertTrue(presupuesto.willRunOut(5000.0, 55, 0.2));
    }

    @Test
    void conReservaDelVeintePorCientoNoCortaSiHayMasDeLoNecesarioMasLaReserva() {
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        assertFalse(presupuesto.willRunOut(5000.0, 65, 0.2));
    }

    @Test
    void sinReservaCortaSoloAlQuedarsePorDebajoDeLoNecesario() {
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        // Hacen falta exactamente 50 cohetes para 5000 bloques a 100 bloques/cohete.
        assertFalse(presupuesto.willRunOut(5000.0, 51, 0.0));
        assertTrue(presupuesto.willRunOut(5000.0, 49, 0.0));
    }

    @Test
    void willRunOutLanzaSiTodaviaNoHayNingunaMedicionDeGasto() {
        FuelBudget presupuesto = new FuelBudget();

        assertThrows(java.util.NoSuchElementException.class,
            () -> presupuesto.willRunOut(1000.0, 10, 0.2));
    }

    // ---------------------------------------------------------------------------------------
    // reserveFraction negativo invertiría la garantía de seguridad: se rechaza
    // ---------------------------------------------------------------------------------------

    @Test
    void unaReservaNegativaLanzaEnVezDeDecirQueLlegaConCeroCohetes() {
        // Caso límite real: tasa 100 bloques/cohete, 5000 bloques por delante -> hacen falta 50
        // cohetes. Con reserva -1.0 el umbral sale en 50 * (1 + (-1.0)) = 0, así que sin la
        // validación willRunOut(5000, 0, -1.0) devolvería false -"no hace falta cortar"- con CERO
        // cohetes en la mano. Tiene que lanzar antes de llegar a ese cálculo.
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        assertThrows(IllegalArgumentException.class, () -> presupuesto.willRunOut(5000.0, 0, -1.0));
    }

    @Test
    void unaReservaNegativaMasSuaveTambienLanza() {
        // Con -0.5 el umbral baja a 25 en vez de subir a 50 + reserva: con 30 cohetes -veinte menos
        // de los que hacen falta a pelo- willRunOut diría "no corta" sin la validación.
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        assertThrows(IllegalArgumentException.class, () -> presupuesto.willRunOut(5000.0, 30, -0.5));
    }

    @Test
    void unaReservaNaNTambienLanza() {
        // NaN compara siempre a falso: rocketsLeft < umbral(NaN) también sería falso sin la
        // validación, colando el mismo fallo por otra puerta.
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        assertThrows(IllegalArgumentException.class,
            () -> presupuesto.willRunOut(5000.0, 0, Double.NaN));
    }

    @Test
    void unaReservaCeroSigueSiendoValida() {
        // Regresión: la validación no puede rechazar el caso normal de "sin margen".
        FuelBudget presupuesto = presupuestoConGastoDe100BloquesPorCohete();

        assertFalse(presupuesto.willRunOut(5000.0, 51, 0.0));
    }

    /** Un presupuesto con un único tramo medido: 1000 bloques por 10 cohetes, 100 por cohete. */
    private static FuelBudget presupuestoConGastoDe100BloquesPorCohete() {
        FuelBudget presupuesto = new FuelBudget();
        presupuesto.sample(0.0, 30);
        presupuesto.sample(1000.0, 20);
        return presupuesto;
    }
}
