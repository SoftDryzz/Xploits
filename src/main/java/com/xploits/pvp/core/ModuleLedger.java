package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>Y un parpadeo tampoco es soltar (rediseño §8).</b> Renunciar al módulo para toda la fase con
 * un único tick observado en "off" convertía una doble pulsación de un bind en quedarte sin él en
 * mitad del combate. Para los módulos que no pueden apagarse solos hace falta además que siga
 * apagado {@link #RELEASE_DEBOUNCE_TICKS} ticks seguidos.
 */
public final class ModuleLedger {
    /**
     * Ticks seguidos que un módulo tomado tiene que estar apagado, mientras la fase lo sigue
     * pidiendo, para concluir que el jugador lo quiere para él (rediseño §8). Cuatro ticks, 0,2 s.
     *
     * <p>El número sale de las dos formas de equivocarse. Por abajo: una doble pulsación de un bind
     * -o un bind que se repite, o abrir y cerrar la ClickGUI encima del mismo módulo- deja el módulo
     * apagado solo los ticks que tardas en volver a pulsar, del orden de dos o tres a velocidad
     * humana (100-150 ms); cuatro ticks los cubren, y como el jugador lo vuelve a encender él mismo,
     * el ledger no tiene ni que retomarlo. Por arriba: un soltado de verdad tarda esos mismos 0,2 s
     * en reconocerse, menos que el ciclo de cristal más corto de §9, así que nunca llega a verse
     * como que el director pelea con el jugador por un bind.
     *
     * <p>Durante la espera el módulo <b>ni se retoma ni se da por soltado</b>: retomarlo sería
     * exactamente la pelea que hay que evitar -el jugador apaga, el director enciende, y la cuenta
     * de ticks apagados nunca llegaría a subir-.
     */
    public static final int RELEASE_DEBOUNCE_TICKS = 4;

    /** Los módulos que este ledger tiene tomados ahora mismo. */
    private final Set<String> owned = new LinkedHashSet<>();

    /**
     * Ticks seguidos que cada módulo tomado lleva observado en "off" mientras la fase lo sigue
     * pidiendo (rediseño §8). Solo tiene entrada durante la espera del antirrebote.
     */
    private final Map<String, Integer> offTicks = new HashMap<>();

    /**
     * Módulos que el jugador soltó a mano mientras la fase los seguía pidiendo. No se vuelven a
     * tomar hasta que la fase cambie o se llame a {@link #reset()} (spec §7): es la memoria que
     * falta para que apagar algo a mano funcione de verdad.
     */
    private final Set<String> releasedThisPhase = new LinkedHashSet<>();

    private CombatState lastPhase;

    /**
     * Si un apagado observado en {@code name} cuenta como "soltado a mano" (spec §7). Busca en
     * {@link ManagedModules#ALL}, el catálogo de los módulos dirigidos, que vive en este mismo
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
            offTicks.clear();
            lastPhase = phase;
        }

        List<String> toDisable = new ArrayList<>();
        List<String> newlyReleased = new ArrayList<>();

        // Primera pasada: reconciliar lo que creíamos tomado con lo que está encendido de verdad.
        for (String name : new ArrayList<>(owned)) {
            if (!active.contains(name)) {
                // Ya no está encendido. Cuatro de los seis módulos ofensivos pueden apagarse solos
                // sin que el jugador los toque (spec §7) -auto-trap al colocar el trap y surround
                // con sus toggle-on-*, de fábrica; auto-city de fábrica si no encuentra
                // objetivo/bloque/pico o tras minar con éxito; auto-anvil solo si el jugador activa
                // toggle-on-break-, y ese apagado no es que el jugador lo soltara a mano: deja de
                // ser nuestro y el director puede volver a tomarlo en la segunda pasada de este
                // mismo tick. Lo mismo si la fase ya no lo pide: no hay nada que soltar.
                if (turnsItselfOff(name) || !wanted.contains(name)) {
                    owned.remove(name);
                    offTicks.remove(name);
                    continue;
                }

                // Para los que NO pueden apagarse solos, un apagado observado apunta al jugador,
                // pero un solo tick no basta (rediseño §8): una doble pulsación de un bind te
                // dejaba sin el módulo en mitad del combate. Mientras dura el antirrebote sigue
                // siendo nuestro y no se retoma -retomarlo sería pelearse con el bind-.
                int ticksOff = offTicks.merge(name, 1, Integer::sum);
                if (ticksOff < RELEASE_DEBOUNCE_TICKS) continue;

                owned.remove(name);
                offTicks.remove(name);
                releasedThisPhase.add(name);
                newlyReleased.add(name);
                continue;
            }
            // Ha vuelto a estar encendido antes de cumplirse el antirrebote: era un parpadeo.
            offTicks.remove(name);
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
            // En pleno antirrebote no se toca: si el jugador lo apagó, reencenderlo sería pelearse
            // con él y además impediría que la cuenta llegara nunca a RELEASE_DEBOUNCE_TICKS.
            if (offTicks.containsKey(name)) continue;
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
        offTicks.clear();
        lastPhase = null;
    }
}
