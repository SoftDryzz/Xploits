package com.xploits.pvp.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLedgerTest {
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

        // Tick 2: el jugador lo apaga a mano; la fase lo sigue pidiendo, pero "active" ya no lo trae.
        ModuleLedger.Result second = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(second.toEnable().isEmpty(), "no debe reencenderlo en el mismo tick en que se soltó");
        assertEquals(List.of("crystal-aura"), second.newlyReleased());
        assertFalse(ledger.owned().contains("crystal-aura"));

        // Tick 3, misma fase: sigue sin tomarlo aunque la fase lo siga pidiendo.
        ModuleLedger.Result third = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertTrue(third.toEnable().isEmpty(), "soltado a mano: no se retoma en la misma fase");
        assertFalse(ledger.owned().contains("crystal-aura"));
    }

    @Test
    void releasingTwiceInARowDoesNotDisableAgainOrRepeatTheNotice() {
        ModuleLedger ledger = new ModuleLedger();
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of()); // se suelta aquí

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
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of()); // soltado en SUPERFICIE
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
        ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of()); // soltado
        assertFalse(ledger.owned().contains("crystal-aura"));

        ledger.reset();

        // Sin reset, seguir en la misma fase no lo habría retomado; con reset, sí.
        ModuleLedger.Result result = ledger.apply(CombatState.SUPERFICIE, Set.of("crystal-aura"), Set.of());
        assertEquals(List.of("crystal-aura"), result.toEnable());
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
}
