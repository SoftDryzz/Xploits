package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.List;
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

    private CombatState state = CombatState.SIN_COMBATE;
    private CombatState pending;
    private int pendingTicks;
    private int ticksInState;

    /**
     * Los módulos que el {@link Plan} del tick anterior devolvió en {@code enable()}. Es la
     * memoria que hace falta para la histéresis del filtro de recursos (spec §6.2): sin ella,
     * {@code planFor} no podría saber si un módulo ya estaba encendido.
     */
    private Set<ManagedModule> previouslyEnabled = Set.of();

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
    }

    /**
     * Ejecuta un ciclo completo del algoritmo (spec §4): clasifica el snapshot en una fase
     * candidata, decide si el director debe moverse a ella -de inmediato si la candidata es
     * {@code SIN_COMBATE}, o solo tras sostenerse {@link #CHANGE_HOLD_TICKS} ticks seguidos y con
     * al menos {@link #MIN_DWELL_TICKS} cumplidos en la fase actual en cualquier otro caso- y
     * devuelve qué módulos debería tener encendidos, filtrados por los recursos que llevas encima
     * (con histéresis: ver {@link #thresholdFor}) y por el suelo de seguridad de los tótems (spec §6).
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
        // en el tick anterior, no lo que acaba de decidir este.
        previouslyEnabled = Set.copyOf(plan.enable());
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
        if (s.targetSurrounded()) return CombatState.RODEADO;
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
            int have = snapshot.amountOf(module.needs());
            if (have < thresholdFor(module)) {
                skipped.add(new Skipped(module, "tienes " + have + ", necesita " + module.minimum()));
                continue;
            }
            enable.add(module);
        }

        // SIN_RECURSOS es cómo se informa, no un sitio donde se vive (spec §4.2).
        CombatState reported = enable.isEmpty() ? CombatState.SIN_RECURSOS : state;
        return new Plan(reported, enable, skipped);
    }

    /**
     * La histéresis del filtro de recursos (spec §6.2): sin ella, un recurso que se va gastando
     * durante la pelea (la obsidiana de auto-trap, por ejemplo) cruza el mínimo una y otra vez y el
     * módulo se enciende y se apaga en cada tick. Un módulo que ya estaba encendido el tick anterior
     * se mantiene con la mitad de su mínimo, redondeando hacia abajo y con un suelo de 1; uno que no
     * lo estaba necesita el mínimo completo para encenderse.
     */
    private int thresholdFor(ManagedModule module) {
        if (!previouslyEnabled.contains(module)) return module.minimum();
        return Math.max(1, module.minimum() / 2);
    }
}
