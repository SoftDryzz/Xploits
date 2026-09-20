package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireworkWatchTest {
    private static final int THRESHOLD = 16;

    private static FireworkWatch watch() {
        return new FireworkWatch(THRESHOLD);
    }

    @Test
    void porEncimaDelUmbralNoSeAvisa() {
        FireworkWatch watch = watch();
        assertFalse(watch.observe(64));
        assertFalse(watch.observe(30));
        assertFalse(watch.observe(THRESHOLD + 1));
    }

    @Test
    void seAvisaJustoAlTocarElUmbral() {
        FireworkWatch watch = watch();
        assertFalse(watch.observe(THRESHOLD + 1));
        assertTrue(watch.observe(THRESHOLD), "con los fuegos del umbral justos ya se avisa");
    }

    /** El vuelo se observa veinte veces por segundo: un aviso por tick taparía todo lo demás. */
    @Test
    void elAvisoNoSeRepiteCadaTick() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(10));

        for (int fireworks = 10; fireworks >= 0; fireworks--) {
            assertFalse(watch.observe(fireworks), "no se repite mientras siga en la banda: " + fireworks);
        }
        for (int i = 0; i < 100; i++) {
            assertFalse(watch.observe(0), "ni siquiera a cero se repite en bucle");
        }
    }

    /**
     * El fallo contrario al bucle: quedarse mudo el resto del viaje. Si el jugador saca fuegos de un
     * shulker y más tarde vuelve a quedarse sin ellos, el aviso tiene que volver a salir.
     */
    @Test
    void reponerFuegosRearmaElAviso() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(5));
        assertFalse(watch.observe(3));

        assertFalse(watch.observe(64), "reponer no avisa, solo rearma");

        assertTrue(watch.observe(5), "tras la reposición el aviso vuelve a poder salir");
    }

    @Test
    void laReposicionTieneQueSacarDeLaBandaParaRearmar() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(5));

        // Subir dentro de la banda no es una reposición que valga: sigue en peligro y ya se avisó.
        assertFalse(watch.observe(THRESHOLD));
        assertFalse(watch.observe(5));

        assertFalse(watch.observe(THRESHOLD + 1), "ahora sí ha salido de la banda");
        assertTrue(watch.observe(THRESHOLD));
    }

    @Test
    void seRearmaTantasVecesComoHagaFalta() {
        FireworkWatch watch = watch();
        for (int round = 0; round < 5; round++) {
            assertFalse(watch.observe(64), "ronda " + round);
            assertTrue(watch.observe(0), "ronda " + round);
            assertFalse(watch.observe(0), "ronda " + round);
        }
    }

    /** Umbral cero: solo se avisa cuando se acaban del todo, y basta uno para rearmar. */
    @Test
    void umbralCeroAvisaSoloAlQuedarseSinNinguno() {
        FireworkWatch watch = new FireworkWatch(0);
        assertFalse(watch.observe(1));
        assertTrue(watch.observe(0));
        assertFalse(watch.observe(0));
        assertFalse(watch.observe(1));
        assertTrue(watch.observe(0));
    }

    @Test
    void empezarYaSinFuegosAvisaEnElPrimerTick() {
        assertTrue(watch().observe(0));
    }

    @Test
    void resetDevuelveElAvisoAArmado() {
        FireworkWatch watch = watch();
        assertTrue(watch.observe(0));
        assertFalse(watch.observe(0));

        watch.reset();

        assertTrue(watch.observe(0), "un viaje nuevo vuelve a avisar aunque el anterior ya lo hiciera");
    }

    @Test
    void elUmbralEsDelConstructorYSeRecuerda() {
        assertEquals(THRESHOLD, watch().threshold());

        FireworkWatch early = new FireworkWatch(40);
        assertTrue(early.observe(40), "con umbral 40 se avisa mucho antes");

        assertFalse(new FireworkWatch(2).observe(40), "con umbral 2 esos mismos 40 no preocupan");
    }

    @Test
    void unUmbralNegativoSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> new FireworkWatch(-1));
    }
}
