package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los números de estos tests están calculados a mano contra {@link ActionWatch#IDLE_TICKS}, no
 * contra la fórmula del código: el margen de fábrica son 60 ticks, así que el tick 59 calla, el 60
 * habla, y un gasto en el tick 30 vuelve a poner el aviso en el 90.
 */
class ActionWatchTest {
    private static final String AURA = ManagedModules.CRYSTAL_AURA.name();
    private static final String TRAP = ManagedModules.AUTO_TRAP.name();
    private static final String SURROUND = ManagedModules.SURROUND.name();
    private static final String FILLER = ManagedModules.HOLE_FILLER.name();

    /** Con objetivo delante y la pila que se le diga en la hotbar. */
    private static CombatSnapshot with(Map<Resource, Integer> resources) {
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources);
    }

    private static CombatSnapshot withCrystals(int crystals) {
        return with(Map.of(Resource.CRYSTALS, crystals));
    }

    private static CombatSnapshot noTarget(int crystals) {
        return Snapshots.of(false, 0, 0, 0, false, false, false, 2, Map.of(Resource.CRYSTALS, crystals));
    }

    /** Alimenta {@code ticks} ticks idénticos y devuelve si en alguno hubo aviso del módulo. */
    private static boolean feed(ActionWatch watch, int ticks, CombatSnapshot snapshot,
                                Set<String> wanted, Set<String> active, ManagedModule module) {
        boolean warned = false;
        for (int i = 0; i < ticks; i++) {
            if (watch.update(snapshot, wanted, active).contains(module)) warned = true;
        }
        return warned;
    }

    @Test
    void elPicoYLosAntiQuedanFuera() {
        // auto-city usa pico, que no se consume; los tres anti- no colocan nada. Vigilar a
        // cualquiera de los cuatro sería avisar de un fallo cada vez que funcionan bien.
        assertFalse(ActionWatch.watches(ManagedModules.AUTO_CITY), "auto-city usa pico, que no baja");
        assertFalse(ActionWatch.watches(ManagedModules.ANTI_ANVIL));
        assertFalse(ActionWatch.watches(ManagedModules.ANTI_BED));
        assertFalse(ActionWatch.watches(ManagedModules.ANTI_ANCHOR));

        assertEquals(List.of(ManagedModules.CRYSTAL_AURA, ManagedModules.AUTO_TRAP,
                ManagedModules.AUTO_WEB, ManagedModules.SURROUND,
                ManagedModules.AUTO_ANVIL, ManagedModules.HOLE_FILLER),
            ActionWatch.WATCHED,
            "seis de los diez: los cuatro ofensivos que gastan y los dos de la obsidiana");
    }

    @Test
    void elMargenEsDeTresSegundosYEsoTieneQueSeguirSiendoVerdad() {
        // El número, clavado a mano: si alguien lo toca, este test es el que lo discute. Por abajo
        // tiene que dejar sitio de sobra a la cadencia legítima más lenta de los vigilados -el
        // `delay` de AutoAnvil, 10 ticks de fábrica, o sea un yunque cada once- y por arriba no hay
        // prisa, porque la causa que busca es permanente.
        assertEquals(60, ActionWatch.IDLE_TICKS, "tres segundos");
        assertTrue(ActionWatch.IDLE_TICKS >= 5 * 11,
            "cinco cadencias de auto-anvil (11 ticks cada una) antes de afirmar nada");
    }

    @Test
    void calladoHastaElMargenYAvisoJustoEnEl() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);

        // 59 ticks contados a mano, no ActionWatch.IDLE_TICKS - 1: uno menos que el margen todavía
        // no afirma nada.
        assertFalse(feed(watch, 59, snapshot, Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA), "59 ticks no son 60");
        assertEquals(59, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));
        assertEquals(List.of(), watch.idle());

        assertEquals(List.of(ManagedModules.CRYSTAL_AURA),
            watch.update(snapshot, Set.of(AURA), Set.of(AURA)), "el tick 60 sí");
        assertEquals(List.of(ManagedModules.CRYSTAL_AURA), watch.idle());
    }

    @Test
    void gastarReinicia() {
        // Gasto en el tick 30: la cuenta vuelve a cero y el aviso se va al tick 90. En el 89 -59
        // ticks después del gasto- todavía no.
        ActionWatch watch = new ActionWatch();
        feed(watch, 30, withCrystals(10), Set.of(AURA), Set.of(AURA), ManagedModules.CRYSTAL_AURA);

        assertEquals(List.of(), watch.update(withCrystals(9), Set.of(AURA), Set.of(AURA)),
            "el cristal ha bajado: está actuando");
        assertEquals(0, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));

        assertFalse(feed(watch, 59, withCrystals(9), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA), "tick 89: 59 ticks desde el gasto");
        assertEquals(List.of(ManagedModules.CRYSTAL_AURA),
            watch.update(withCrystals(9), Set.of(AURA), Set.of(AURA)), "tick 90");
    }

    @Test
    void recogerMaterialTambienReinicia() {
        // Una subida no es un gasto, pero rompe la serie igual: puede tapar uno (gastas una,
        // recoges dos) y ya no se está comparando lo mismo.
        ActionWatch watch = new ActionWatch();
        feed(watch, ActionWatch.IDLE_TICKS - 1, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA);

        assertEquals(List.of(), watch.update(withCrystals(14), Set.of(AURA), Set.of(AURA)));
        assertEquals(0, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void seDiceUnaSolaVezYNoEnBucle() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, snapshot, Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA));

        assertFalse(feed(watch, 200, snapshot, Set.of(AURA), Set.of(AURA), ManagedModules.CRYSTAL_AURA),
            "diez segundos más de lo mismo y ni una línea más");
        // Pero la situación se sigue viendo: el aviso calla, el contador no.
        assertEquals(List.of(ManagedModules.CRYSTAL_AURA), watch.idle());
        assertEquals(ActionWatch.IDLE_TICKS + 200, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void siLaSituacionCambiaSeRearma() {
        ActionWatch watch = new ActionWatch();
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA));

        // Se acaba la pelea: un solo tick sin objetivo rearma.
        watch.update(noTarget(10), Set.of(), Set.of());
        assertEquals(List.of(), watch.idle());

        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA), "en la pelea siguiente se vuelve a avisar");
    }

    @Test
    void perderElObjetivoReiniciaLaCuenta() {
        ActionWatch watch = new ActionWatch();
        feed(watch, ActionWatch.IDLE_TICKS - 1, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA);

        watch.update(noTarget(10), Set.of(AURA), Set.of(AURA));
        assertEquals(0, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));

        assertFalse(feed(watch, ActionWatch.IDLE_TICKS - 1, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA), "la cuenta empezó de cero, no siguió");
    }

    @Test
    void sinMaterialNoSeCuenta() {
        // Sin cristales el aura no puede colocar: que no gaste es lo normal y no se afirma nada.
        // De eso ya avisa el plan por su cuenta (rediseño §7).
        ActionWatch watch = new ActionWatch();
        assertFalse(feed(watch, 200, withCrystals(0), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA));
        assertEquals(0, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void porDebajoDelMinimoDelModuloTampoco() {
        // auto-trap necesita ocho obsidianas para el trap entero: con siete no puede actuar.
        ActionWatch watch = new ActionWatch();
        CombatSnapshot siete = with(Map.of(Resource.OBSIDIAN, 7));
        assertFalse(feed(watch, 200, siete, Set.of(TRAP), Set.of(TRAP), ManagedModules.AUTO_TRAP));

        // Con el mínimo justo sí se mide. Va en un vigilante nuevo a propósito: pasar de siete a
        // ocho es la pila moviéndose, y eso reinicia por su cuenta -lo que aquí se comprueba es el
        // mínimo, no el reinicio-.
        CombatSnapshot ocho = with(Map.of(Resource.OBSIDIAN, 8));
        assertTrue(feed(new ActionWatch(), ActionWatch.IDLE_TICKS, ocho, Set.of(TRAP), Set.of(TRAP),
            ManagedModules.AUTO_TRAP), "con ocho sí podía, y no gastó");
    }

    @Test
    void apagadoONoQueridoNoCuentan() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);

        assertFalse(feed(watch, 200, snapshot, Set.of(AURA), Set.of(), ManagedModules.CRYSTAL_AURA),
            "el plan lo quiere pero no está encendido: no hay nada que medir");
        assertFalse(feed(watch, 200, snapshot, Set.of(), Set.of(AURA), ManagedModules.CRYSTAL_AURA),
            "está encendido pero es del jugador, no lo pide el plan");
    }

    @Test
    void laObsidianaEsDeTresYElGastoLesValeALosTres() {
        // No se puede saber quién colocó: el inventario solo dice que hay una menos. Se le concede
        // a los tres, que es el lado barato -un aviso tarde, no uno falso-.
        ActionWatch watch = new ActionWatch();
        Set<String> tres = Set.of(TRAP, SURROUND, FILLER);
        for (int i = 0; i < 30; i++) watch.update(with(Map.of(Resource.OBSIDIAN, 20)), tres, tres);
        assertEquals(30, watch.idleTicksOf(ManagedModules.SURROUND));

        watch.update(with(Map.of(Resource.OBSIDIAN, 19)), tres, tres);
        assertEquals(0, watch.idleTicksOf(ManagedModules.AUTO_TRAP));
        assertEquals(0, watch.idleTicksOf(ManagedModules.SURROUND));
        assertEquals(0, watch.idleTicksOf(ManagedModules.HOLE_FILLER));

        // Y desde ahí, los tres cumplen el margen a la vez: tick 59 callados, tick 90 los tres.
        for (int i = 0; i < 59; i++) {
            assertEquals(List.of(), watch.update(with(Map.of(Resource.OBSIDIAN, 19)), tres, tres));
        }
        assertEquals(List.of(ManagedModules.AUTO_TRAP, ManagedModules.SURROUND, ManagedModules.HOLE_FILLER),
            watch.update(with(Map.of(Resource.OBSIDIAN, 19)), tres, tres));
    }

    @Test
    void unaPilaQueNoEsLaSuyaNoLeReinicia() {
        // El aura vive de los cristales: que la obsidiana baje no dice nada de ella.
        ActionWatch watch = new ActionWatch();
        Set<String> dos = Set.of(AURA, FILLER);
        for (int i = 0; i < ActionWatch.IDLE_TICKS - 1; i++) {
            watch.update(with(Map.of(Resource.CRYSTALS, 10, Resource.OBSIDIAN, 20)), dos, dos);
        }
        assertEquals(List.of(ManagedModules.CRYSTAL_AURA),
            watch.update(with(Map.of(Resource.CRYSTALS, 10, Resource.OBSIDIAN, 19)), dos, dos),
            "el hole-filler gastó y se libra; el aura no");
    }

    @Test
    void elAvisoNombraAlModuloYAlSospechoso() {
        String aura = ActionWatch.reason(ManagedModules.CRYSTAL_AURA);
        assertTrue(aura.startsWith("crystal-aura lleva 3 s encendido"), aura);
        assertTrue(aura.contains("cristales"), aura);
        assertTrue(aura.contains("min-damage"), aura);
        assertTrue(aura.contains("support"), aura);
        assertTrue(aura.contains("No lo apago"), aura);

        String filler = ActionWatch.reason(ManagedModules.HOLE_FILLER);
        assertTrue(filler.startsWith("hole-filler lleva 3 s encendido"), filler);
        assertTrue(filler.contains("obsidiana"), filler);
        assertTrue(filler.contains("only-moving"), filler);
        assertTrue(filler.contains("no haya ningún hueco"), filler);

        // Ninguno afirma una causa: todos dejan abierto que no haya posición válida y ninguno
        // propone apagar nada.
        for (ManagedModule module : ActionWatch.WATCHED) {
            String reason = ActionWatch.reason(module);
            assertTrue(reason.startsWith(module.name() + " lleva "), reason);
            assertTrue(reason.contains("no lo apago") || reason.contains("No lo apago"), reason);
            assertFalse(reason.contains("apágalo"), reason);
        }
    }

    @Test
    void resetOlvidaLaCuentaYLoAvisado() {
        ActionWatch watch = new ActionWatch();
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA));

        watch.reset();
        assertEquals(0, watch.idleTicksOf(ManagedModules.CRYSTAL_AURA));
        assertEquals(List.of(), watch.idle());
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            ManagedModules.CRYSTAL_AURA), "tras el reset se vuelve a contar desde cero");
    }
}
