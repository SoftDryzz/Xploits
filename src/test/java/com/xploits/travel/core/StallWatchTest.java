package com.xploits.travel.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallWatchTest {
    private static final int LIMIT_TICKS = 30 * StallWatch.TICKS_PER_SECOND;
    private static final double EPSILON = 1.0;
    private static final int FIRST = 0;

    private static StallWatch watch() {
        return new StallWatch(LIMIT_TICKS, EPSILON);
    }

    /**
     * Corre {@code ticks} ticks a la misma distancia y devuelve el tick (contando desde 1) en el que
     * la vigilancia cortó, o 0 si aguantó todos.
     */
    private static int stillFor(StallWatch watch, int waypoint, double distance, int ticks) {
        for (int i = 1; i <= ticks; i++) {
            if (watch.tick(waypoint, distance)) return i;
        }
        return 0;
    }

    @Test
    void elPrimerTickDeUnWaypointNuncaEsAtasco() {
        assertFalse(watch().tick(FIRST, 8_000));
    }

    /** El caso de la spec §10, clavado: 29 segundos sin acercarse no corta; 30 sí. */
    @Test
    void veintinueveSegundosNoCortanYTreintaSi() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);

        int twentyNineSeconds = 29 * StallWatch.TICKS_PER_SECOND;
        assertEquals(0, stillFor(watch, FIRST, 8_000, twentyNineSeconds),
            "a los 29 segundos todavía no se corta");

        assertEquals(LIMIT_TICKS - twentyNineSeconds, stillFor(watch, FIRST, 8_000, StallWatch.TICKS_PER_SECOND),
            "el corte cae exactamente en el tick 600, el de los 30 segundos");
    }

    @Test
    void acercarseReiniciaLaCuenta() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);
        stillFor(watch, FIRST, 8_000, LIMIT_TICKS - 1);

        assertFalse(watch.tick(FIRST, 7_000), "se ha acercado mil bloques: no hay atasco");
        assertEquals(0, watch.ticksWithoutProgress());
        assertEquals(0, stillFor(watch, FIRST, 7_000, LIMIT_TICKS - 1), "la cuenta empieza otra vez desde cero");
    }

    /**
     * Acercarse menos que el epsilon no es avanzar: si contara, el vaivén de un bloque que da el
     * propio vuelo rearmaría el contador eternamente y la vigilancia no cortaría nunca.
     */
    @Test
    void acercarsePorDebajoDelEpsilonNoCuentaComoAvance() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);

        // Medio bloque en los treinta segundos enteros: menos que el epsilon, así que no es avance
        // por mucho que la distancia baje en todos y cada uno de los ticks.
        double step = 0.5 / LIMIT_TICKS;
        double distance = 8_000;
        int cut = 0;
        for (int i = 1; i <= LIMIT_TICKS; i++) {
            distance -= step;
            if (watch.tick(FIRST, distance)) {
                cut = i;
                break;
            }
        }

        assertEquals(LIMIT_TICKS, cut, "acercarse medio bloque en 30 segundos no debe impedir el corte");
    }

    /** Y el epsilon es un parámetro, no una constante escondida: con uno mayor, el medio bloque tampoco. */
    @Test
    void elEpsilonEsDelConstructor() {
        StallWatch tight = new StallWatch(LIMIT_TICKS, 0.1);
        tight.tick(FIRST, 8_000);
        assertFalse(tight.tick(FIRST, 7_999.5), "con epsilon 0,1 medio bloque sí es avance");

        StallWatch loose = new StallWatch(LIMIT_TICKS, 50);
        loose.tick(FIRST, 8_000);
        loose.tick(FIRST, 7_999.5);
        assertEquals(1, loose.ticksWithoutProgress(), "con epsilon 50 medio bloque no es avance");
    }

    @Test
    void elLimiteEsDelConstructor() {
        StallWatch quick = new StallWatch(3, EPSILON);
        quick.tick(FIRST, 100);
        assertEquals(3, stillFor(quick, FIRST, 100, 10));
        assertEquals(3, new StallWatch(60, EPSILON).limitSeconds());
    }

    /**
     * El caso que cortaría un viaje perfecto: al pasar de waypoint la distancia salta hacia arriba
     * de golpe. Ni ese salto ni los ticks quietos del waypoint anterior pueden arrastrarse al nuevo.
     */
    @Test
    void cambiarDeWaypointReiniciaLaVigilancia() {
        StallWatch watch = watch();
        watch.tick(0, 8_000);
        stillFor(watch, 0, 8_000, LIMIT_TICKS - 1);
        assertEquals(LIMIT_TICKS - 1, watch.ticksWithoutProgress(), "a un tick de cortar");

        // Se alcanza el waypoint 0 y se apunta al 1, que está a 12 000 bloques: la distancia sube.
        assertFalse(watch.tick(1, 12_000), "el salto de waypoint no es un atasco");
        assertEquals(0, watch.ticksWithoutProgress(), "la cuenta del waypoint anterior no se arrastra");

        // Y la distancia del waypoint viejo no es la referencia del nuevo: 11 000 sigue siendo
        // avance aunque sea mucho peor que los 8 000 de antes.
        assertFalse(watch.tick(1, 11_000));
        assertEquals(0, stillFor(watch, 1, 11_000, LIMIT_TICKS - 1),
            "el waypoint nuevo tiene sus propios 30 segundos enteros");
    }

    @Test
    void elWaypointNuevoSiPuedeAtascarse() {
        StallWatch watch = watch();
        watch.tick(0, 8_000);
        watch.tick(1, 12_000);
        assertEquals(LIMIT_TICKS, stillFor(watch, 1, 12_000, LIMIT_TICKS));
    }

    @Test
    void resetDevuelveLaVigilanciaAlPrincipio() {
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);
        stillFor(watch, FIRST, 8_000, LIMIT_TICKS - 1);

        watch.reset();

        assertEquals(0, watch.ticksWithoutProgress());
        assertFalse(watch.tick(FIRST, 8_000), "tras el reset, el primer tick vuelve a fijar la referencia");
        assertEquals(0, stillFor(watch, FIRST, 8_000, LIMIT_TICKS - 1));
    }

    @Test
    void unaDistanciaQueSubeSinCambiarDeWaypointSiEsAtasco() {
        // Alejarse no es acercarse: si Baritone se va en dirección contraria, eso se corta igual.
        StallWatch watch = watch();
        watch.tick(FIRST, 8_000);
        int cut = 0;
        for (int i = 1; i <= LIMIT_TICKS; i++) {
            if (watch.tick(FIRST, 8_000 + i)) {
                cut = i;
                break;
            }
        }
        assertEquals(LIMIT_TICKS, cut);
    }

    @Test
    void losParametrosDegeneradosSeRechazan() {
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(0, EPSILON));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(-1, EPSILON));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(LIMIT_TICKS, -0.5));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(LIMIT_TICKS, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new StallWatch(LIMIT_TICKS, Double.POSITIVE_INFINITY));
    }

    @Test
    void ofSecondsTraduceAVeinteTicksPorSegundo() {
        StallWatch watch = StallWatch.ofSeconds(30, EPSILON);
        watch.tick(FIRST, 100);
        assertEquals(LIMIT_TICKS, stillFor(watch, FIRST, 100, LIMIT_TICKS));
        assertEquals(30, watch.limitSeconds());
    }

    @Test
    void epsilonCeroSigueCortandoSiLaDistanciaNoSeMueve() {
        StallWatch watch = new StallWatch(LIMIT_TICKS, 0);
        watch.tick(FIRST, 8_000);
        assertEquals(LIMIT_TICKS, stillFor(watch, FIRST, 8_000, LIMIT_TICKS));
        assertTrue(watch.ticksWithoutProgress() >= LIMIT_TICKS);
    }
}
