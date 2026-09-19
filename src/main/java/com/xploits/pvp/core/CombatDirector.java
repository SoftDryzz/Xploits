package com.xploits.pvp.core;

import java.util.ArrayList;
import java.util.List;

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

    public CombatState state() {
        return state;
    }

    public int ticksInState() {
        return ticksInState;
    }

    /** Olvida la fase y los contadores. Se llama al encender el módulo. */
    public void reset() {
        state = CombatState.SIN_COMBATE;
        pending = null;
        pendingTicks = 0;
        ticksInState = 0;
    }

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
        return planFor(state, snapshot);
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

    private static Plan planFor(CombatState state, CombatSnapshot snapshot) {
        List<ManagedModule> wanted = modulesFor(state);
        if (wanted.isEmpty()) return new Plan(state, List.of(), List.of());

        List<ManagedModule> enable = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (ManagedModule module : wanted) {
            // El suelo de seguridad: una aura de cristales sin tótem te mata a ti (spec §6.1).
            if (module.equals(ManagedModules.CRYSTAL_AURA) && snapshot.selfTotems() <= 0) {
                skipped.add(new Skipped(module, "no llevas tótems"));
                continue;
            }
            int have = snapshot.amountOf(module.needs());
            if (have < module.minimum()) {
                skipped.add(new Skipped(module, "tienes " + have + ", necesita " + module.minimum()));
                continue;
            }
            enable.add(module);
        }

        // SIN_RECURSOS es cómo se informa, no un sitio donde se vive (spec §4.2).
        CombatState reported = enable.isEmpty() ? CombatState.SIN_RECURSOS : state;
        return new Plan(reported, enable, skipped);
    }
}
