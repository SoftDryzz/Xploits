package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * La propiedad de los módulos dirigidos, en lógica pura (spec §7): a partir de qué pide la fase,
 * qué está encendido de verdad ahora mismo y qué llevaba tomado, decide qué encender, qué apagar y
 * de qué se acaba de soltar el jugador. No sabe nada de Meteor ni de Minecraft: el adaptador solo
 * le pasa nombres y booleanos, y ejecuta lo que este devuelve llamando a {@code enable()} y
 * {@code disable()} de verdad.
 *
 * <p>Esta decisión vivía en el adaptador, sin tests, y por eso un fallo de un solo tick -la
 * primera pasada soltaba lo que el jugador acababa de apagar a mano, y la segunda lo volvía a
 * encender porque seguía en lo que pedía la fase- llegó hasta el review final (spec §12). Aquí es
 * lógica pura y se puede probar sin arrancar el juego.
 *
 * <p><b>Un apagado observado no siempre es un soltado a mano (spec §7).</b> Cuatro de los seis
 * módulos dirigidos pueden apagarse solos sin que el jugador los toque -no los cuatro por el mismo
 * motivo, ni todos de fábrica: {@code auto-trap} tras colocar el trap y {@code surround} con sus
 * ajustes {@code toggle-on-*}, los dos de fábrica; {@code auto-city} de fábrica si no encuentra
 * objetivo, bloque o pico, o tras minar con éxito; y {@code auto-anvil} solo si el jugador activa
 * {@code toggle-on-break}, que es {@code false} de fábrica-, así que tratar ese apagado como un
 * soltado a mano bloqueaba la fase entera y avisaba de un "lo apagaste tú" falso. La
 * distinción es por módulo ({@link ManagedModule#turnsItselfOff()}), no por tiempo: no hay ventana
 * de ticks que sirva para los seis a la vez, porque {@code auto-trap} se apaga muchos ticks después
 * de tomarlo y {@code auto-city} puede apagarse dentro del mismo {@code onActivate()} que dispara
 * su encendido.
 */
public final class ModuleLedger {
    /** Los módulos que este ledger tiene tomados ahora mismo. */
    private final Set<String> owned = new LinkedHashSet<>();

    /**
     * Módulos que el jugador soltó a mano mientras la fase los seguía pidiendo. No se vuelven a
     * tomar hasta que la fase cambie o se llame a {@link #reset()} (spec §7): es la memoria que
     * falta para que apagar algo a mano funcione de verdad.
     */
    private final Set<String> releasedThisPhase = new LinkedHashSet<>();

    private CombatState lastPhase;

    /**
     * Si un apagado observado en {@code name} cuenta como "soltado a mano" (spec §7). Busca en
     * {@link ManagedModules#ALL}, el catálogo de los seis módulos dirigidos, que vive en este mismo
     * paquete: no hace falta ningún adaptador para consultarlo, sigue siendo lógica pura. Un nombre
     * que no está en el catálogo (por ejemplo uno de los "de siempre") nunca llega aquí como
     * "owned", así que el valor por defecto (false) no importa en la práctica.
     */
    private static boolean turnsItselfOff(String name) {
        for (ManagedModule module : ManagedModules.ALL) {
            if (module.name().equals(name)) return module.turnsItselfOff();
        }
        return false;
    }

    /**
     * Lo que hay que hacer este tick: qué encender de verdad, qué apagar de verdad, y de qué
     * módulos se acaba de enterar que el jugador los soltó a mano (para avisar una vez, no en cada
     * tick que siguen sin tomarse).
     */
    public record Result(List<String> toEnable, List<String> toDisable, List<String> newlyReleased) {
        public Result {
            toEnable = List.copyOf(toEnable);
            toDisable = List.copyOf(toDisable);
            newlyReleased = List.copyOf(newlyReleased);
        }
    }

    /**
     * Decide la propiedad de este tick. No enciende ni apaga nada por sí solo: el adaptador ejecuta
     * el {@link Result} que devuelve.
     *
     * @param phase  la fase física actual (no la que informa el plan); un cambio de fase olvida lo
     *               que el jugador soltó a mano (spec §7)
     * @param wanted los nombres de módulo que el plan de este tick quiere encendidos
     * @param active los nombres de módulo que están encendidos de verdad ahora mismo, los tome
     *               quien los tome
     */
    public Result apply(CombatState phase, Set<String> wanted, Set<String> active) {
        if (phase != lastPhase) {
            releasedThisPhase.clear();
            lastPhase = phase;
        }

        List<String> toDisable = new ArrayList<>();
        List<String> newlyReleased = new ArrayList<>();

        // Primera pasada: reconciliar lo que creíamos tomado con lo que está encendido de verdad.
        for (String name : new ArrayList<>(owned)) {
            if (!active.contains(name)) {
                // Ya no está encendido: deja de ser nuestro en cualquier caso. Pero cuatro de los
                // seis módulos dirigidos pueden apagarse solos sin que el jugador los toque (spec
                // §7) -auto-trap al colocar el trap y surround con sus toggle-on-*, de fábrica;
                // auto-city de fábrica si no encuentra objetivo/bloque/pico o tras minar con éxito;
                // auto-anvil solo si el jugador activa toggle-on-break-, y ese apagado no es que
                // el jugador lo soltara a mano. Solo para los módulos que NO
                // pueden apagarse solos (turnsItselfOff() == false) un apagado observado cuenta
                // como soltado: bloquea la fase y avisa. Para los demás, el director puede
                // volver a tomarlo en la segunda pasada de este mismo tick.
                owned.remove(name);
                if (wanted.contains(name) && !turnsItselfOff(name)) {
                    releasedThisPhase.add(name);
                    newlyReleased.add(name);
                }
                continue;
            }
            if (!wanted.contains(name)) {
                toDisable.add(name);
                owned.remove(name);
            }
        }

        // Segunda pasada: tomar lo que falta. Nunca lo que ya está encendido -sea nuestro o del
        // jugador-, y nunca lo que el jugador acaba de soltar en esta misma fase.
        List<String> toEnable = new ArrayList<>();
        for (String name : wanted) {
            if (active.contains(name)) continue;
            if (releasedThisPhase.contains(name)) continue;
            toEnable.add(name);
            owned.add(name);
        }

        return new Result(toEnable, toDisable, newlyReleased);
    }

    /** Lo que el ledger tiene tomado ahora mismo. */
    public Set<String> owned() {
        return Set.copyOf(owned);
    }

    /** Olvida lo tomado y lo soltado a mano. Se llama al encender o apagar el módulo entero. */
    public void reset() {
        owned.clear();
        releasedThisPhase.clear();
        lastPhase = null;
    }
}
