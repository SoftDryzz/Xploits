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

    private static CombatSnapshot obsidian(int blocks) {
        return with(Map.of(Resource.OBSIDIAN, blocks));
    }

    private static CombatSnapshot noTarget(int crystals) {
        return Snapshots.of(false, 0, 0, 0, false, false, false, 2, Map.of(Resource.CRYSTALS, crystals));
    }

    /** Alimenta {@code ticks} ticks idénticos y devuelve si en alguno hubo veredicto de esa pila. */
    private static boolean feed(ActionWatch watch, int ticks, CombatSnapshot snapshot,
                                Set<String> wanted, Set<String> active, Resource resource) {
        boolean warned = false;
        for (int i = 0; i < ticks; i++) {
            for (ActionWatch.Idle idle : watch.update(snapshot, wanted, active)) {
                if (idle.resource() == resource) warned = true;
            }
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
        assertEquals(List.of(Resource.CRYSTALS, Resource.OBSIDIAN, Resource.WEBS, Resource.ANVILS),
            ActionWatch.WATCHED_RESOURCES, "cuatro pilas, y la obsidiana una sola vez para los tres");
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
        assertFalse(feed(watch, 59, snapshot, Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "59 ticks no son 60");
        assertEquals(59, watch.idleTicksOf(Resource.CRYSTALS));
        assertEquals(List.of(), watch.idle());

        List<ActionWatch.Idle> sesenta = watch.update(snapshot, Set.of(AURA), Set.of(AURA));
        assertEquals(1, sesenta.size(), "el tick 60 sí");
        assertEquals(List.of(ManagedModules.CRYSTAL_AURA), sesenta.getFirst().modules());
        assertEquals(60, sesenta.getFirst().ticks());
        assertFalse(sesenta.getFirst().joint(), "el aura es la única que bebe de los cristales");
        assertEquals(1, watch.idle().size());
    }

    @Test
    void gastarReinicia() {
        // Gasto en el tick 30: la cuenta vuelve a cero y el aviso se va al tick 90. En el 89 -59
        // ticks después del gasto- todavía no.
        ActionWatch watch = new ActionWatch();
        feed(watch, 30, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS);

        assertEquals(List.of(), watch.update(withCrystals(9), Set.of(AURA), Set.of(AURA)),
            "el cristal ha bajado: está actuando");
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));

        assertFalse(feed(watch, 59, withCrystals(9), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "tick 89: 59 ticks desde el gasto");
        assertEquals(1, watch.update(withCrystals(9), Set.of(AURA), Set.of(AURA)).size(), "tick 90");
    }

    @Test
    void recogerMaterialTambienReinicia() {
        // Una subida no es un gasto, pero rompe la serie igual: puede tapar uno (gastas una,
        // recoges dos) y ya no se está comparando lo mismo.
        ActionWatch watch = new ActionWatch();
        feed(watch, 59, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS);

        assertEquals(List.of(), watch.update(withCrystals(14), Set.of(AURA), Set.of(AURA)));
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));
    }

    @Test
    void seDiceUnaSolaVezYNoEnBucle() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, snapshot, Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS));

        assertFalse(feed(watch, 200, snapshot, Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "diez segundos más de lo mismo y ni una línea más");
        // Pero la situación se sigue viendo: el aviso calla, el contador no.
        assertEquals(1, watch.idle().size());
        assertEquals(260, watch.idleTicksOf(Resource.CRYSTALS));
        assertEquals(260, watch.idle().getFirst().ticks());
    }

    @Test
    void siLaSituacionCambiaSeRearma() {
        ActionWatch watch = new ActionWatch();
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS));

        // Se acaba la pelea: un solo tick sin objetivo rearma.
        watch.update(noTarget(10), Set.of(), Set.of());
        assertEquals(List.of(), watch.idle());

        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS), "en la pelea siguiente se vuelve a avisar");
    }

    @Test
    void perderElObjetivoReiniciaLaCuenta() {
        ActionWatch watch = new ActionWatch();
        feed(watch, 59, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS);

        watch.update(noTarget(10), Set.of(AURA), Set.of(AURA));
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));

        assertFalse(feed(watch, 59, withCrystals(10), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS),
            "la cuenta empezó de cero, no siguió");
    }

    @Test
    void sinMaterialNoSeCuenta() {
        // Sin cristales el aura no puede colocar: que no gaste es lo normal y no se afirma nada.
        // De eso ya avisa el plan por su cuenta (rediseño §7).
        ActionWatch watch = new ActionWatch();
        assertFalse(feed(watch, 200, withCrystals(0), Set.of(AURA), Set.of(AURA), Resource.CRYSTALS));
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));
    }

    @Test
    void porDebajoDelMinimoDelModuloTampoco() {
        // auto-trap necesita ocho obsidianas para el trap entero: con siete no puede actuar, y es
        // el único de los tres que se pide aquí, así que la pila entera se queda sin nadie.
        ActionWatch watch = new ActionWatch();
        assertFalse(feed(watch, 200, obsidian(7), Set.of(TRAP), Set.of(TRAP), Resource.OBSIDIAN));

        // Con el mínimo justo sí se mide. Va en un vigilante nuevo a propósito: pasar de siete a
        // ocho es la pila moviéndose, y eso reinicia por su cuenta -lo que aquí se comprueba es el
        // mínimo, no el reinicio-.
        assertTrue(feed(new ActionWatch(), ActionWatch.IDLE_TICKS, obsidian(8), Set.of(TRAP),
            Set.of(TRAP), Resource.OBSIDIAN), "con ocho sí podía, y no gastó");
    }

    @Test
    void apagadoONoQueridoNoCuentan() {
        ActionWatch watch = new ActionWatch();
        CombatSnapshot snapshot = withCrystals(10);

        assertFalse(feed(watch, 200, snapshot, Set.of(AURA), Set.of(), Resource.CRYSTALS),
            "el plan lo quiere pero no está encendido: no hay nada que medir");
        assertFalse(feed(watch, 200, snapshot, Set.of(), Set.of(AURA), Resource.CRYSTALS),
            "está encendido pero es del jugador, no lo pide el plan");
    }

    @Test
    void laObsidianaEsDeTresYElGastoLesValeALosTres() {
        // No se puede saber quién colocó: el inventario solo dice que hay una menos. Se le concede
        // a los tres, que es el lado barato -un aviso tarde, no uno falso-.
        ActionWatch watch = new ActionWatch();
        Set<String> tres = Set.of(TRAP, SURROUND, FILLER);
        for (int i = 0; i < 30; i++) watch.update(obsidian(20), tres, tres);
        assertEquals(30, watch.idleTicksOf(Resource.OBSIDIAN));

        watch.update(obsidian(19), tres, tres);
        assertEquals(0, watch.idleTicksOf(Resource.OBSIDIAN));

        // Y desde ahí, el veredicto vuelve a cumplirse a la vez: tick 59 callado, tick 90 uno solo
        // con los tres nombres dentro.
        for (int i = 0; i < 59; i++) {
            assertEquals(List.of(), watch.update(obsidian(19), tres, tres));
        }
        List<ActionWatch.Idle> noventa = watch.update(obsidian(19), tres, tres);
        assertEquals(1, noventa.size(), "un veredicto, no tres");
        assertEquals(List.of(ManagedModules.AUTO_TRAP, ManagedModules.SURROUND, ManagedModules.HOLE_FILLER),
            noventa.getFirst().modules());
        assertTrue(noventa.getFirst().joint());
    }

    @Test
    void elVeredictoDeLaPilaCompartidaNoSeRepartePorModulo() {
        // Es lo que la medida sostiene y nada más: la pila no baja, y el inventario no dice quién
        // coloca. Si se informara de los tres por separado se estaría afirmando una certeza por
        // módulo que no existe.
        ActionWatch watch = new ActionWatch();
        Set<String> tres = Set.of(TRAP, SURROUND, FILLER);
        List<ActionWatch.Idle> veredictos = null;
        for (int i = 0; i < ActionWatch.IDLE_TICKS; i++) {
            List<ActionWatch.Idle> tick = watch.update(obsidian(20), tres, tres);
            if (!tick.isEmpty()) veredictos = tick;
        }

        assertEquals(1, veredictos.size());
        ActionWatch.Idle idle = veredictos.getFirst();
        assertTrue(idle.joint());
        assertEquals(3, idle.modules().size());

        String aviso = ActionWatch.reason(idle);
        assertTrue(aviso.startsWith("auto-trap, surround y hole-filler llevan 3 s encendidos"), aviso);
        assertTrue(aviso.contains("No puedo decirte cuál de los 3 falla"), aviso);
        assertTrue(aviso.contains("no quién la colocó"), aviso);
        assertTrue(aviso.contains("no ha colocado ninguno"), aviso);
        // Y sigue nombrando los sospechosos de cada uno, que es para lo que sirve.
        assertTrue(aviso.contains("auto-trap: whitelist"), aviso);
        assertTrue(aviso.contains("surround: blocks"), aviso);
        assertTrue(aviso.contains("hole-filler: only-moving"), aviso);
    }

    @Test
    void unoSoloDeLaPilaCompartidaSeInformaComoSuyo() {
        // Si el plan solo quiere hole-filler, nadie más está en condiciones de gastar esa obsidiana
        // y el veredicto sí es de él: no hay certeza que inventar.
        ActionWatch watch = new ActionWatch();
        List<ActionWatch.Idle> veredictos = null;
        for (int i = 0; i < ActionWatch.IDLE_TICKS; i++) {
            List<ActionWatch.Idle> tick = watch.update(obsidian(20), Set.of(FILLER), Set.of(FILLER));
            if (!tick.isEmpty()) veredictos = tick;
        }

        ActionWatch.Idle idle = veredictos.getFirst();
        assertFalse(idle.joint());
        assertEquals(List.of(ManagedModules.HOLE_FILLER), idle.modules());
        assertTrue(ActionWatch.reason(idle).startsWith("hole-filler lleva 3 s encendido"),
            ActionWatch.reason(idle));
    }

    @Test
    void queCambieElGrupoReinicia() {
        // auto-trap se suma en el tick 50: el veredicto de los dos no puede apoyarse en los
        // cincuenta ticks en los que solo estaba hole-filler. Con 20 obsidianas los dos llegan a su
        // mínimo, así que el cambio es del grupo y no de la pila.
        ActionWatch watch = new ActionWatch();
        for (int i = 0; i < 50; i++) watch.update(obsidian(20), Set.of(FILLER), Set.of(FILLER));
        assertEquals(50, watch.idleTicksOf(Resource.OBSIDIAN));

        Set<String> dos = Set.of(FILLER, TRAP);
        watch.update(obsidian(20), dos, dos);
        assertEquals(1, watch.idleTicksOf(Resource.OBSIDIAN), "empieza de cero con el grupo nuevo");

        assertFalse(feed(watch, 58, obsidian(20), dos, dos, Resource.OBSIDIAN), "van 59");
        assertTrue(feed(watch, 1, obsidian(20), dos, dos, Resource.OBSIDIAN), "el 60 del grupo nuevo");
    }

    @Test
    void unaPilaQueNoEsLaSuyaNoLeReinicia() {
        // El aura vive de los cristales: que la obsidiana baje no dice nada de ella.
        ActionWatch watch = new ActionWatch();
        Set<String> dos = Set.of(AURA, FILLER);
        for (int i = 0; i < 59; i++) {
            watch.update(with(Map.of(Resource.CRYSTALS, 10, Resource.OBSIDIAN, 20)), dos, dos);
        }
        List<ActionWatch.Idle> sesenta =
            watch.update(with(Map.of(Resource.CRYSTALS, 10, Resource.OBSIDIAN, 19)), dos, dos);
        assertEquals(1, sesenta.size(), "el hole-filler gastó y se libra; el aura no");
        assertEquals(Resource.CRYSTALS, sesenta.getFirst().resource());
    }

    @Test
    void elAvisoNombraAlModuloYAlSospechoso() {
        for (ManagedModule module : ActionWatch.WATCHED) {
            ActionWatch.Idle idle =
                new ActionWatch.Idle(module.needs(), List.of(module), ActionWatch.IDLE_TICKS);
            String reason = ActionWatch.reason(idle);
            assertTrue(reason.startsWith(module.name() + " lleva 3 s encendido"), reason);
            assertTrue(reason.contains("No lo apago"), reason);
            assertFalse(reason.contains("apágalo"), reason);
        }

        String aura = ActionWatch.reason(
            new ActionWatch.Idle(Resource.CRYSTALS, List.of(ManagedModules.CRYSTAL_AURA), 60));
        assertTrue(aura.contains("cristales"), aura);
        assertTrue(aura.contains("min-damage"), aura);
        assertTrue(aura.contains("support"), aura);

        String filler = ActionWatch.reason(
            new ActionWatch.Idle(Resource.OBSIDIAN, List.of(ManagedModules.HOLE_FILLER), 60));
        assertTrue(filler.contains("obsidiana"), filler);
        assertTrue(filler.contains("only-moving"), filler);
        assertTrue(filler.contains("no haya ningún hueco"), filler);
    }

    @Test
    void laWhitelistDeAutoTrapNoLlevaBloqueDeNetherita() {
        String trap = ActionWatch.reason(
            new ActionWatch.Idle(Resource.OBSIDIAN, List.of(ManagedModules.AUTO_TRAP), 60));
        assertTrue(trap.contains("obsidiana llorosa"), trap);
        assertTrue(trap.contains("el bloque de netherita NO está en ella"), trap);
    }

    @Test
    void autoWebNombraSuCausaInocente() {
        // Una telaraña no es reemplazable, así que en cuanto la casilla prevista tiene telaraña
        // auto-web deja de colocar ahí, legítimamente. Es su equivalente al surround completo.
        String web = ActionWatch.reason(
            new ActionWatch.Idle(Resource.WEBS, List.of(ManagedModules.AUTO_WEB), 60));
        assertTrue(web.contains("ya tenga telaraña"), web);
        assertTrue(web.contains("no se sustituye"), web);
        assertTrue(web.indexOf("telaraña no se sustituye") < web.indexOf("los sospechosos son"),
            "la causa inocente va delante de los sospechosos: " + web);
    }

    @Test
    void resetOlvidaLaCuentaYLoAvisado() {
        ActionWatch watch = new ActionWatch();
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS));

        watch.reset();
        assertEquals(0, watch.idleTicksOf(Resource.CRYSTALS));
        assertEquals(List.of(), watch.idle());
        assertTrue(feed(watch, ActionWatch.IDLE_TICKS, withCrystals(10), Set.of(AURA), Set.of(AURA),
            Resource.CRYSTALS), "tras el reset se vuelve a contar desde cero");
    }
}
