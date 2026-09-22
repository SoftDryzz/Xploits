package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLedgerTest {
    /**
     * Observa el módulo apagado los ticks que haga falta para que el antirrebote de §8 lo dé por
     * soltado, y devuelve el resultado del tick en que eso ocurre.
     */
    private static ModuleLedger.Result releaseByHand(ModuleLedger ledger, CombatState phase, String name) {
        ModuleLedger.Result result = null;
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS; i++) {
            result = ledger.apply(phase, Set.of(name), Set.of());
        }
        return result;
    }

    @Test
    void aModuleTheUserTurnedOnByHandIsNeverDisabled() {
        ModuleLedger ledger = new ModuleLedger();

        // "crystal-aura" está encendido pero no lo pide la fase actual: es del jugador, no del
        // ledger, porque nunca pasó por su lado de "toEnable".
        ModuleLedger.Result result = ledger.apply(CombatState.ACERCAMIENTO, Set.of(), Set.of("crystal-aura"));

        assertTrue(result.toDisable().isEmpty(), "no es suyo: nunca debe apagarlo");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void aTakenModuleTheUserTurnsOffByHandStopsBeingOursAndIsNotRetakenInThatPhase() {
        ModuleLedger ledger = new ModuleLedger();

        // Tick 1: la fase pide crystal-aura, nada encendido todavía -> el ledger lo enciende y lo toma.
        ModuleLedger.Result first = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), first.toEnable());
        assertTrue(ledger.owned().contains("crystal-aura"));

        // El jugador lo apaga a mano y lo deja apagado: pasado el antirrebote, es suyo.
        ModuleLedger.Result released = releaseByHand(ledger, CombatState.SUPERFICIE, "crystal-aura");
        assertEquals(List.of("crystal-aura"), released.newlyReleased());
        assertTrue(released.toEnable().isEmpty(), "no debe reencenderlo en el tick en que se soltó");
        assertFalse(ledger.owned().contains("crystal-aura"));

        // Misma fase: sigue sin tomarlo aunque la fase lo siga pidiendo.
        ModuleLedger.Result next = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(next.toEnable().isEmpty(), "soltado a mano: no se retoma en la misma fase");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void releasingTwiceInARowDoesNotDisableAgainOrRepeatTheNotice() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        releaseByHand(ledger, CombatState.SUPERFICIE, "crystal-aura");

        // Dos ticks más sin que nada cambie: nunca vuelve a pedir el apagado ni a repetir el aviso.
        for (int i = 0; i < 2; i++) {
            ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
            assertTrue(result.toDisable().isEmpty(), "ya está apagado: no hay nada que volver a apagar");
            assertTrue(result.newlyReleased().isEmpty(), "el aviso de soltado ya se dio una vez");
        }
    }

    @Test
    void aPhaseChangeForgetsWhatWasReleasedByHand() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        releaseByHand(ledger, CombatState.SUPERFICIE, "crystal-aura");
        assertFalse(ledger.owned().contains("crystal-aura"));

        // La fase cambia (por ejemplo a RODEADO, que también pide crystal-aura) y vuelve a pedirlo:
        // ahora sí se retoma, porque cambiar de fase olvida lo soltado a mano.
        ModuleLedger.Result result = ledger.apply(CombatState.RODEADO, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable());
        assertTrue(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void aModuleNoLongerWantedIsDisabledAndDropped() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of("crystal-aura")); // ya activo

        // La fase deja de pedirlo: se apaga porque es nuestro, no porque el jugador lo soltara.
        ModuleLedger.Result result = ledger.apply(CombatState.ACERCAMIENTO, Set.of(), Set.of("crystal-aura"));
        assertEquals(List.of("crystal-aura"), result.toDisable());
        assertTrue(result.newlyReleased().isEmpty(), "esto no es un soltado a mano");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void theAlwaysOnModulesNeverAppearAsOwnedOrReleased() {
        ModuleLedger ledger = new ModuleLedger();

        // auto-totem, auto-armor, offhand y auto-weapon nunca entran en "wanted": el director no
        // los dirige (spec §7). Aunque estén activos, el ledger no debe tocarlos para nada.
        Set<String> active = Set.of("auto-totem", "auto-armor", "offhand", "auto-weapon", "crystal-aura");
        ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), active);

        assertTrue(result.toDisable().isEmpty());
        assertTrue(result.newlyReleased().isEmpty());
        assertFalse(ledger.owned().contains("auto-totem"));
        assertFalse(ledger.owned().contains("auto-armor"));
        assertFalse(ledger.owned().contains("offhand"));
        assertFalse(ledger.owned().contains("auto-weapon"));
    }

    @Test
    void resetForgetsOwnershipAndReleases() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        releaseByHand(ledger, CombatState.SUPERFICIE, "crystal-aura");
        assertFalse(ledger.owned().contains("crystal-aura"));

        ledger.reset();

        // Sin reset, seguir en la misma fase no lo habría retomado; con reset, sí.
        ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable());
    }

    @Test
    void resetForgetsAnUnfinishedDebounce() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of()); // un tick apagado

        ledger.reset();

        ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable(), "la cuenta a medias no sobrevive al reset");
    }

    @Test
    void aSteadyStateModuleIsNeitherReenabledNorDisabled() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());

        // Sigue queriéndose, sigue encendido, sigue siendo nuestro: no hay nada que hacer.
        ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of("crystal-aura"));
        assertTrue(result.toEnable().isEmpty());
        assertTrue(result.toDisable().isEmpty());
        assertTrue(ledger.owned().contains("crystal-aura"));
    }

    // --- CRÍTICO B: un apagado propio de Meteor no es un soltado a mano (spec §7) ---

    @Test
    void aSelfOffModuleThatTurnsItselfOffIsRetakenAndDoesNotWarn() {
        ModuleLedger ledger = new ModuleLedger();

        // Tick 1: la fase pide auto-trap, nada encendido todavía -> el ledger lo enciende y lo toma.
        ModuleLedger.Result first = ledger.apply(CombatState.SUPERFICIE, Set.of("auto-trap"), Set.of());
        assertEquals(List.of("auto-trap"), first.toEnable());
        assertTrue(ledger.owned().contains("auto-trap"));

        // Tick 2: auto-trap se apagó solo (self-toggle, tras colocar el trap) - la fase lo sigue
        // pidiendo. Al ser un módulo que se apaga solo, esto NO es un soltado a mano: no debe
        // bloquear la fase ni generar aviso, y el director debe poder volver a tomarlo. Tampoco
        // pasa por el antirrebote: esperar cuatro ticks a recolocar el trap sería esperar de más.
        ModuleLedger.Result second = ledger.apply(CombatState.SUPERFICIE, Set.of("auto-trap"), Set.of());
        assertTrue(second.newlyReleased().isEmpty(), "un apagado propio de Meteor no es un soltado a mano");
        assertEquals(List.of("auto-trap"), second.toEnable(), "el director debe poder volver a encenderlo en el mismo tick");
        assertTrue(ledger.owned().contains("auto-trap"));
    }

    @Test
    void crystalAuraTurnedOffByHandStaysBlockedAndWarns() {
        ModuleLedger ledger = new ModuleLedger();

        // Tick 1: toma crystal-aura, que NO se apaga solo (turnsItselfOff() == false).
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(ledger.owned().contains("crystal-aura"));

        ModuleLedger.Result released = releaseByHand(ledger, CombatState.SUPERFICIE, "crystal-aura");
        assertEquals(List.of("crystal-aura"), released.newlyReleased());
        assertTrue(released.toEnable().isEmpty(), "soltado a mano: no se retoma en el mismo tick");
        assertFalse(ledger.owned().contains("crystal-aura"));

        ModuleLedger.Result next = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(next.toEnable().isEmpty(), "sigue bloqueado el resto de la fase");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void theSelfOffMarkDoesNotChangeOwnedOrReleasedOrSteadyStateBehaviour() {
        ModuleLedger ledger = new ModuleLedger();

        // auto-anvil se apaga solo, pero mientras siga encendido y siga pidiéndose, el
        // comportamiento de "tomado" y "estado estable" es idéntico al de un módulo normal.
        ModuleLedger.Result first = ledger.apply(CombatState.ENTERRADO, Set.of("auto-anvil"), Set.of());
        assertEquals(List.of("auto-anvil"), first.toEnable());
        assertTrue(ledger.owned().contains("auto-anvil"));

        ModuleLedger.Result steady = ledger.apply(CombatState.ENTERRADO, Set.of("auto-anvil"), Set.of("auto-anvil"));
        assertTrue(steady.toEnable().isEmpty());
        assertTrue(steady.toDisable().isEmpty());
        assertTrue(ledger.owned().contains("auto-anvil"));

        // Y cuando la fase deja de pedirlo mientras sigue activo, se apaga como cualquier otro
        // módulo tomado -esto no es un "soltado", es la fase soltándolo.
        ModuleLedger.Result noLongerWanted = ledger.apply(CombatState.SIN_COMBATE, Set.of(), Set.of("auto-anvil"));
        assertEquals(List.of("auto-anvil"), noLongerWanted.toDisable());
        assertTrue(noLongerWanted.newlyReleased().isEmpty());
        assertFalse(ledger.owned().contains("auto-anvil"));
    }

    // --- §8: un parpadeo no es soltar ---

    @Test
    void aSingleOffTickDoesNotReleaseTheModule() {
        // §11: un "off" de un tick no suelta el módulo. Una doble pulsación de un bind te dejaba
        // sin él en mitad del combate.
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());

        ModuleLedger.Result blip = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());

        assertTrue(blip.newlyReleased().isEmpty(), "un tick apagado no es un soltado");
        assertTrue(ledger.owned().contains("crystal-aura"), "sigue siendo nuestro mientras dura el antirrebote");
        assertTrue(blip.toEnable().isEmpty(),
            "y tampoco se reenciende: pelearse con el bind impediría que la cuenta subiera nunca");
    }

    @Test
    void aDoublePressComesBackWithoutLosingOwnership() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());

        // Apagado dos ticks (la separación de una doble pulsación humana) y encendido de nuevo por
        // el propio jugador.
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        ModuleLedger.Result back = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of("crystal-aura"));

        assertTrue(back.newlyReleased().isEmpty());
        assertTrue(back.toDisable().isEmpty());
        assertTrue(ledger.owned().contains("crystal-aura"));

        // Y la cuenta vuelve a cero: otro parpadeo suelto tampoco lo suelta.
        ModuleLedger.Result another = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(another.newlyReleased().isEmpty(), "el antirrebote se reinicia al volver a verlo encendido");
    }

    @Test
    void anOffThatLastsTheWholeDebounceDoesRelease() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());

        ModuleLedger.Result result = null;
        for (int i = 0; i < ModuleLedger.RELEASE_DEBOUNCE_TICKS - 1; i++) {
            result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
            assertTrue(result.newlyReleased().isEmpty(), "tick " + i + ": todavía dentro del antirrebote");
        }

        result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.newlyReleased(), "sostenido sí es un soltado");
    }

    @Test
    void theDebounceIsFourTicks() {
        assertEquals(4, ModuleLedger.RELEASE_DEBOUNCE_TICKS,
            "0,2 s: más que una doble pulsación humana, menos que un ciclo de cristal");
    }

    @Test
    void aModuleTheUserTurnsOffAndThePhaseStopsWantingIsNotAReleaseEither() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(ledger.owned().contains("crystal-aura"), "precondición: es nuestro");

        // Se apaga y además la fase deja de pedirlo: no hay nada que soltar ni de qué avisar.
        ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of(), Set.of());
        assertTrue(result.newlyReleased().isEmpty());
        assertTrue(result.toDisable().isEmpty(), "ya está apagado");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }
}
