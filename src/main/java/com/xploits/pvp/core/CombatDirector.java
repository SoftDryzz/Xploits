package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La máquina de estados del combate (spec §4). Decide la fase a partir del snapshot y devuelve qué
 * módulos deberían estar encendidos, filtrados por lo que el jugador lleva encima.
 *
 * <p>No sabe nada de encender ni apagar: eso es del adaptador, que además lleva la cuenta de qué
 * módulos tomó él (spec §7).
 */
public final class CombatDirector {
    /** Ticks que una condición nueva debe mantenerse para provocar el cambio de fase. */
    public static final int CHANGE_HOLD_TICKS = 10;
    /** Ticks mínimos dentro de una fase antes de poder abandonarla. */
    public static final int MIN_DWELL_TICKS = 20;
    /**
     * Ticks seguidos por debajo del mínimo que un módulo ya encendido aguanta antes de soltarse
     * (spec §6.2). Es una permanencia distinta de {@link #MIN_DWELL_TICKS}: esa es de la fase
     * entera, esta es del recurso de un módulo concreto.
     */
    public static final int RESOURCE_RELEASE_DWELL_TICKS = 20;

    /**
     * Distancia máxima real al bloque de rodeado para clasificar {@code RODEADO} (spec §4.2.1,
     * segunda corrección). Verificado contra las fuentes de {@code meteor-client:1.21.11-SNAPSHOT}
     * (`AutoCity.java`): el módulo se apaga solo -dentro de su propio
     * {@code onActivate()}/{@code onTick()}, con un error en el chat- si el bloque de rodeado está a
     * más de {@code break-range} (por defecto **4.5**, el valor de fábrica que fija esta constante)
     * de ti, comprobado con {@code PlayerUtils.squaredDistanceTo(targetPos)} sobre la {@code BlockPos}
     * del bloque. El otro límite de {@code auto-city} -{@code target-range}, contra el objetivo, no
     * el bloque- es {@link #AUTO_CITY_TARGET_RANGE}: los dos hacen falta a la vez (tercera
     * corrección), esta constante por sí sola ya no basta.
     *
     * <p><b>4.5 es el ajuste de fábrica de {@code break-range}; el usuario puede cambiarlo en
     * Meteor.</b> Esta constante no lo lee en vivo -el núcleo no importa nada de
     * {@code meteordevelopment}-, así que si alguien sube o baja su {@code break-range} el director
     * sigue comparando contra 4.5, no contra el valor real configurado. Es el mismo trato que recibe
     * {@link #AUTO_CITY_TARGET_RANGE}.
     *
     * <p><b>La comparación es contra la distancia real al bloque, no al objetivo</b>
     * ({@link CombatSnapshot#cityBlockDistance()}). Usar la distancia al objetivo como proxy -lo
     * que hacía la primera corrección- es incorrecto: el bloque de rodeado es un vecino horizontal
     * del objetivo (`EntityUtils.getCityBlock()`) y puede caer al lado contrario de donde estás tú,
     * así que un objetivo cerca no garantiza un bloque cerca. Contraejemplo real: jugador en
     * (0.5, 0, 0.5), objetivo en (4.5, 0, 0.5) -distancia 4.0, dentro del antiguo límite-, bloque de
     * rodeado en (5, 0, 0) -distancia al cuadrado 20.5, por encima de 4.5² = 20.25-: la versión
     * anterior declaraba RODEADO y auto-city se apagaba solo, con error, cada tick. Además
     * {@code PlayerUtils.squaredDistanceTo(BlockPos)} mide a la esquina mínima del bloque, no a su
     * centro, así que ni siquiera una cota "conservadora" basada en el objetivo puede acotar bien la
     * distancia real al bloque.
     */
    public static final double AUTO_CITY_BREAK_RANGE = 4.5;

    /**
     * Distancia máxima real al objetivo (no al bloque) para clasificar {@code RODEADO} (spec
     * §4.2.1, tercera corrección). {@link #AUTO_CITY_BREAK_RANGE} por sí sola no basta: verificado
     * en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT} (`AutoCity.onTick()` llama primero a
     * {@code TargetUtils.isBadTarget(target, targetRange.get())}, que exige
     * {@code PlayerUtils.isWithin(target, targetRange)}, <b>antes</b> de mirar el bloque para nada),
     * {@code auto-city} también se apaga solo -mismo `toggle()` incondicional, mismo error en el
     * chat- si el objetivo mismo está a más de {@code target-range} (por defecto **5.5**, el valor
     * de fábrica que fija esta constante), sin que la distancia al bloque importe en absoluto.
     *
     * <p>El bloque de rodeado es un vecino horizontal del objetivo medido a su esquina mínima
     * ({@code EntityUtils.getCityBlock()} / {@code PlayerUtils.squaredDistanceTo(BlockPos)}), así
     * que un bloque a &le; {@link #AUTO_CITY_BREAK_RANGE} (4.5) admite un objetivo hasta a
     * &asymp;6.4: sin esta cota, ese hueco entre 5.5 y ~6.4 volvía a declarar {@code RODEADO} con el
     * objetivo fuera del alcance real de {@code auto-city}, que se apagaba solo cada tick y el
     * ledger lo volvía a encender -la misma tormenta de veinte encendidos y veinte errores por
     * segundo que la corrección de {@link #AUTO_CITY_BREAK_RANGE} ya había eliminado para el caso
     * contrario (objetivo cerca, bloque lejos)-.
     *
     * <p>5.5 es el ajuste de fábrica de {@code target-range}; mismo trato que
     * {@link #AUTO_CITY_BREAK_RANGE}: no se lee en vivo del ajuste real del usuario.
     */
    public static final double AUTO_CITY_TARGET_RANGE = 5.5;

    private CombatState state = CombatState.SIN_COMBATE;
    private CombatState pending;
    private int pendingTicks;
    private int ticksInState;

    /**
     * Los módulos que el {@link Plan} del tick anterior devolvió en {@code enable()}. Es la
     * memoria que hace falta para la histéresis del filtro de recursos (spec §6.2): sin ella,
     * {@code planFor} no podría saber si un módulo ya estaba encendido.
     *
     * <p>Solo se olvida en {@link #reset()}. Antes se sobrescribía también al entrar en
     * {@code SIN_COMBATE}, que se entra sin esperar (spec §4.4): un objetivo que sale un tick de
     * rango y vuelve borraba toda la memoria de recursos de la pelea entera. Ahora {@link #tick}
     * deja este campo intacto mientras la fase física es {@code SIN_COMBATE}.
     */
    private Set<ManagedModule> previouslyEnabled = Set.of();

    /**
     * Ticks seguidos que cada módulo lleva por debajo de su mínimo mientras sigue encendido por
     * histéresis (spec §6.2). Solo tiene entrada mientras el módulo está en su ventana de gracia;
     * se borra en cuanto vuelve a tener suficiente o se le acaba la permanencia.
     */
    private final Map<ManagedModule, Integer> belowMinimumTicks = new HashMap<>();

    /**
     * La fase física en la que está el director ahora mismo. Nunca es {@code SIN_RECURSOS}: esa
     * fase solo aparece en el {@link Plan} que devuelve {@link #tick}, no aquí (spec §4.2).
     */
    public CombatState state() {
        return state;
    }

    /** Ticks que lleva el director en la fase actual, contando desde el último cambio. */
    public int ticksInState() {
        return ticksInState;
    }

    /** Olvida la fase, los contadores y qué módulos tenía encendidos. Se llama al encender el módulo. */
    public void reset() {
        state = CombatState.SIN_COMBATE;
        pending = null;
        pendingTicks = 0;
        ticksInState = 0;
        previouslyEnabled = Set.of();
        belowMinimumTicks.clear();
    }

    /**
     * Ejecuta un ciclo completo del algoritmo (spec §4): clasifica el snapshot en una fase
     * candidata, decide si el director debe moverse a ella -de inmediato si la candidata es
     * {@code SIN_COMBATE}, o solo tras sostenerse {@link #CHANGE_HOLD_TICKS} ticks seguidos y con
     * al menos {@link #MIN_DWELL_TICKS} cumplidos en la fase actual en cualquier otro caso- y
     * devuelve qué módulos debería tener encendidos, filtrados por los recursos que llevas encima
     * (con histéresis: ver {@link #planFor}) y por el suelo de seguridad de los tótems (spec §6).
     *
     * @param snapshot         la situación de este tick, ya traducida a valores simples (spec §5)
     * @param approachDistance distancia a partir de la cual el objetivo se considera lejos, no cerca
     * @return el plan de este tick: la fase con la que se informa (puede ser {@code SIN_RECURSOS}
     *     aunque la fase física siga siendo otra), los módulos a encender y los que se omitieron
     *     junto con el motivo
     */
    public Plan tick(CombatSnapshot snapshot, int approachDistance) {
        CombatState candidate = classify(snapshot, approachDistance);

        if (candidate == CombatState.SIN_COMBATE) {
            // Soltar tarde nunca es aceptable: se entra sin esperar (spec §4.4).
            enter(CombatState.SIN_COMBATE);
        } else if (candidate != state) {
            pendingTicks = candidate == pending ? pendingTicks + 1 : 1;
            pending = candidate;
            // La permanencia evita que oscile entre fases de combate, pero NO debe retrasar el
            // enganche: salir de SIN_COMBATE solo exige que la condición se mantenga (spec §4.4).
            boolean dwellMet = ticksInState >= MIN_DWELL_TICKS || state == CombatState.SIN_COMBATE;
            if (pendingTicks >= CHANGE_HOLD_TICKS && dwellMet) enter(candidate);
        } else {
            pending = null;
            pendingTicks = 0;
        }

        ticksInState++;
        Plan plan = planFor(state, snapshot);
        // Se guarda DESPUÉS de calcular el plan: planFor() necesita ver lo que estaba encendido
        // en el tick anterior, no lo que acaba de decidir este. Mientras la fase física sea
        // SIN_COMBATE no se toca: un blip de un solo tick sin objetivo no debe borrar la memoria
        // de recursos de la pelea que sigue (spec §6.2, corrige el borrado de §4.4).
        if (state != CombatState.SIN_COMBATE) {
            previouslyEnabled = Set.copyOf(plan.enable());
        }
        return plan;
    }

    private void enter(CombatState next) {
        if (next != state) {
            state = next;
            ticksInState = 0;
        }
        pending = null;
        pendingTicks = 0;
    }

    /** La precedencia de spec §4.2: gana la primera que se cumpla. */
    private static CombatState classify(CombatSnapshot s, int approachDistance) {
        if (!s.hasTarget()) return CombatState.SIN_COMBATE;
        if (s.selfGliding() || s.targetGliding()) return CombatState.PERSECUCION;
        if (s.targetBurrowed()) return CombatState.ENTERRADO;
        if (s.targetSurrounded() && s.cityBlockDistance() <= AUTO_CITY_BREAK_RANGE
            && s.targetDistance() <= AUTO_CITY_TARGET_RANGE) return CombatState.RODEADO;
        if (s.targetDistance() > approachDistance) return CombatState.ACERCAMIENTO;
        return CombatState.SUPERFICIE;
    }

    private static List<ManagedModule> modulesFor(CombatState state) {
        return switch (state) {
            case ACERCAMIENTO -> List.of(ManagedModules.SURROUND);
            case SUPERFICIE -> List.of(ManagedModules.CRYSTAL_AURA, ManagedModules.AUTO_TRAP, ManagedModules.AUTO_WEB);
            case RODEADO -> List.of(ManagedModules.AUTO_CITY, ManagedModules.CRYSTAL_AURA);
            case ENTERRADO -> List.of(ManagedModules.AUTO_ANVIL);
            case PERSECUCION -> List.of(ManagedModules.AUTO_WEB);
            case SIN_COMBATE, SIN_RECURSOS -> List.of();
        };
    }

    private Plan planFor(CombatState state, CombatSnapshot snapshot) {
        List<ManagedModule> wanted = modulesFor(state);
        if (wanted.isEmpty()) return new Plan(state, List.of(), List.of());

        List<ManagedModule> enable = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (ManagedModule module : wanted) {
            // El suelo de seguridad: una aura de cristales sin tótem te mata a ti (spec §6.1). Es
            // binario a propósito, sin histéresis: la cuenta de tótems no oscila sola, baja cuando
            // te salva uno, y ahí apagar los cristales es lo correcto (spec §6.2).
            if (module.equals(ManagedModules.CRYSTAL_AURA) && snapshot.selfTotems() <= 0) {
                skipped.add(new Skipped(module, "no llevas tótems"));
                continue;
            }
            if (hasEnough(module, snapshot, enable)) continue;
            skipped.add(new Skipped(module, "tienes " + snapshot.amountOf(module.needs()) + ", necesita " + module.minimum()));
        }

        // SIN_RECURSOS es cómo se informa, no un sitio donde se vive (spec §4.2).
        CombatState reported = enable.isEmpty() ? CombatState.SIN_RECURSOS : state;
        return new Plan(reported, enable, skipped);
    }

    /**
     * La histéresis del filtro de recursos (spec §6.2): sin ella, un recurso que se va gastando
     * durante la pelea (la obsidiana de auto-trap, por ejemplo) cruza el mínimo una y otra vez y el
     * módulo se enciende y se apaga en cada tick.
     *
     * <p>Un módulo que no estaba encendido el tick anterior necesita el mínimo completo, sin
     * gracia. Uno que sí lo estaba se mantiene encendido mientras lleve menos de
     * {@link #RESOURCE_RELEASE_DWELL_TICKS} ticks seguidos por debajo del mínimo; al cumplirlos, se
     * suelta. Es una permanencia en el tiempo, no un umbral partido: con {@code minimum() == 1}
     * -cuatro de los seis módulos dirigidos- un umbral a la mitad redondeaba al mismo mínimo y no
     * daba ninguna gracia; contar ticks sirve igual para los seis.
     */
    private boolean hasEnough(ManagedModule module, CombatSnapshot snapshot, List<ManagedModule> enable) {
        int have = snapshot.amountOf(module.needs());
        if (have >= module.minimum()) {
            belowMinimumTicks.remove(module);
            enable.add(module);
            return true;
        }

        if (!previouslyEnabled.contains(module)) {
            belowMinimumTicks.remove(module);
            return false;
        }

        int ticksBelow = belowMinimumTicks.merge(module, 1, Integer::sum);
        if (ticksBelow < RESOURCE_RELEASE_DWELL_TICKS) {
            enable.add(module);
            return true;
        }

        belowMinimumTicks.remove(module);
        return false;
    }
}
