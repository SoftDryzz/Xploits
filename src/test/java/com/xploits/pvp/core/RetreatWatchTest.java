package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetreatWatchTest {
    private static CombatSnapshot at(double distance) {
        return at(distance, "enemigo");
    }

    private static CombatSnapshot at(double distance, String id) {
        return new CombatSnapshot(true, distance, false, 0, false, false, false, 2, Map.of())
            .withTargetId(id);
    }

    /** Alimenta la ventana entera con la misma distancia y devuelve el último veredicto. */
    private static boolean fill(RetreatWatch watch, double distance) {
        boolean retreating = false;
        for (int i = 0; i <= RetreatWatch.WINDOW_TICKS; i++) retreating = watch.update(at(distance));
        return retreating;
    }

    @Test
    void aTargetJustSeenIsNeverPullingAway() {
        // Hasta que la ventana no está llena no hay terreno que comparar: no se afirma nada.
        RetreatWatch watch = new RetreatWatch();
        for (int i = 0; i < RetreatWatch.WINDOW_TICKS; i++) {
            assertFalse(watch.update(at(2.0 + i)), "tick " + i + ": la ventana todavía no está llena");
        }
    }

    @Test
    void standingStillIsNotPullingAway() {
        assertFalse(fill(new RetreatWatch(), 3.0));
    }

    @Test
    void gainingAWholeBlockOverTheWindowIsPullingAway() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        boolean retreating = false;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) {
            retreating = watch.update(at(2.0 + i * (RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS)));
        }
        assertTrue(retreating);
    }

    @Test
    void gainingLessThanTheThresholdIsNot() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        boolean retreating = false;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) {
            retreating = watch.update(at(2.0 + i * 0.05)); // 0,5 bloques en la ventana entera
        }
        assertFalse(retreating, "medio bloque en medio segundo es moverse, no irse");
    }

    @Test
    void knockbackDoesNotCountAsPullingAway() {
        // Un golpe te separa unos 0,4 bloques de golpe y ahí se queda.
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        assertFalse(watch.update(at(2.4)));
        assertFalse(fill(watch, 2.4), "y quieto a la nueva distancia tampoco");
    }

    @Test
    void orbitingAroundHimNeverFlipsTheAnswer() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 3.0);

        for (int i = 0; i < 100; i++) {
            assertFalse(watch.update(at(i % 2 == 0 ? 2.5 : 3.5)), "tick " + i);
        }
    }

    @Test
    void theAnswerDoesNotOscillateAtTheThreshold() {
        // La banda muerta: una vez dentro, hace falta bajar de STOP_GAIN para salir, así que una
        // diferencia que ronde justo START_GAIN no puede encender y apagar auto-web en ticks
        // alternos.
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        // Se aleja lo justo para entrar.
        double step = RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) watch.update(at(2.0 + i * step));
        assertTrue(watch.retreating(), "precondición");

        // Y ahora sigue ganando terreno un poco más despacio, rondando el umbral de entrada.
        double distance = 2.0 + RetreatWatch.START_GAIN;
        for (int i = 0; i < 50; i++) {
            distance += step * 0.9;
            assertTrue(watch.update(at(distance)), "tick " + i + ": sigue yéndose, no debe parpadear");
        }
    }

    @Test
    void stoppingEndsIt() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);
        double step = RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) watch.update(at(2.0 + i * step));
        assertTrue(watch.retreating());

        assertFalse(fill(watch, 2.0 + RetreatWatch.START_GAIN), "se paró: deja de irse");
    }

    @Test
    void losingTheTargetForgetsTheSeries() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);
        watch.update(CombatSnapshot.none());

        assertFalse(watch.retreating());
        // Un salto enorme justo después no debe declarar nada: la serie empieza de cero.
        assertFalse(watch.update(at(9.0)));
    }

    @Test
    void changingTargetForgetsTheSeries() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);

        // Otro jugador, mucho más lejos: comparar su distancia con la del anterior daría un salto
        // enorme y una telaraña a nadie.
        assertFalse(watch.update(at(9.0, "otro")));
        for (int i = 0; i < RetreatWatch.WINDOW_TICKS; i++) {
            assertFalse(watch.update(at(9.0, "otro")), "tick " + i);
        }
    }

    @Test
    void resetForgetsEverything() {
        RetreatWatch watch = new RetreatWatch();
        fill(watch, 2.0);
        double step = RetreatWatch.START_GAIN / RetreatWatch.WINDOW_TICKS;
        for (int i = 1; i <= RetreatWatch.WINDOW_TICKS; i++) watch.update(at(2.0 + i * step));
        assertTrue(watch.retreating());

        watch.reset();
        assertFalse(watch.retreating());
        assertFalse(watch.update(at(20.0)));
    }

    @Test
    void theWindowIsHalfASecond() {
        assertEquals(10, RetreatWatch.WINDOW_TICKS, "medio segundo, la unidad de tiempo de combate de §9");
        assertEquals(1.0, RetreatWatch.START_GAIN, 0.0);
        assertEquals(0.25, RetreatWatch.STOP_GAIN, 0.0);
        assertTrue(RetreatWatch.STOP_GAIN < RetreatWatch.START_GAIN, "sin banda muerta, oscila");
    }
}
