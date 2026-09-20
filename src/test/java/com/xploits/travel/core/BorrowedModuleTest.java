package com.xploits.travel.core;

import com.xploits.travel.core.BorrowedModule.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorrowedModuleTest {
    /** elytra-fly: la preparación lo apaga siempre (spec §6.2, paso 5). */
    private static BorrowedModule elytraFly() {
        return new BorrowedModule("elytra-fly", false);
    }

    /** elytra-replace: la preparación lo enciende siempre (spec §6.2, paso 5). */
    private static BorrowedModule elytraReplace() {
        return new BorrowedModule("elytra-replace", true);
    }

    @Test
    void takingAModuleThatIsInTheWayTurnsItOffAndLandingGivesItBack() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.APAGAR, module.take(true));
        assertEquals(Action.ENCENDER, module.release(false, true));
    }

    @Test
    void takingAModuleThatIsMissingTurnsItOnAndLandingGivesItBack() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.ENCENDER, module.take(false));
        assertEquals(Action.APAGAR, module.release(true, true));
    }

    /**
     * El caso que motivó todo esto: un jugador que nunca usa elytra-fly no puede aterrizar con
     * elytra-fly encendido. Con el reposo declarado en un ajuste de valor true, aterrizaba encendido.
     */
    @Test
    void aModuleThePlayerNeverUsedIsNotTurnedOnByTheLanding() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.NADA, module.take(false), "ya estaba apagado: no hay nada que apagar");
        assertEquals(Action.NADA, module.release(false, true), "estaba apagado antes y sigue apagado");
    }

    @Test
    void aModuleThatWasAlreadyOnStaysOn() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.NADA, module.take(true));
        assertEquals(Action.NADA, module.release(true, true));
    }

    /** Doctrina de ModuleLedger: lo que el jugador mueve a mano manda sobre lo anotado. */
    @Test
    void aManualMoveDuringTheFlightWins() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.APAGAR, module.take(true));
        // El jugador lo vuelve a encender a mitad de vuelo: al aterrizar no se toca.
        assertEquals(Action.NADA, module.release(true, true));
        assertFalse(module.hasPending());
    }

    @Test
    void aManualMoveDuringTheFlightWinsTheOtherWayRound() {
        BorrowedModule module = elytraReplace();
        assertEquals(Action.ENCENDER, module.take(false));
        // El jugador lo apaga a mitad de vuelo: al aterrizar no se vuelve a apagar ni se enciende.
        assertEquals(Action.NADA, module.release(false, true));
    }

    @Test
    void releasingWithoutHavingTakenDoesNothing() {
        BorrowedModule module = elytraFly();
        assertEquals(Action.NADA, module.release(true, true));
        assertEquals(Action.NADA, module.release(false, true));
        assertFalse(module.hasPending());
    }

    /** Salida del mundo: no se puede tocar ningún módulo, así que la devolución queda pendiente. */
    @Test
    void whenTheModuleCannotBeToggledTheDecisionIsLeftPending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        assertEquals(Action.NADA, module.release(false, false), "no se toca nada durante el desmontaje");
        assertTrue(module.hasPending());
        assertEquals(Action.ENCENDER, module.pending());
    }

    @Test
    void claimingThePendingHandsItOverOnlyOnce() {
        BorrowedModule module = elytraReplace();
        module.take(false);
        module.release(true, false);
        assertEquals(Action.APAGAR, module.claimPending());
        assertFalse(module.hasPending());
        assertEquals(Action.NADA, module.claimPending());
    }

    /**
     * Los seis caminos de salida se solapan (spec §6.3): el apagado del módulo llega justo detrás de
     * la salida del mundo. El segundo no puede borrar lo que apuntó el primero.
     */
    @Test
    void aSecondReleaseDoesNotWipeThePending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        module.release(false, false);
        assertEquals(Action.NADA, module.release(false, true), "ya no está prestado");
        assertTrue(module.hasPending());
        assertEquals(Action.ENCENDER, module.pending());
    }

    @Test
    void withNothingToUndoTheresNoPendingEither() {
        BorrowedModule module = elytraFly();
        module.take(false);
        assertEquals(Action.NADA, module.release(false, false));
        assertFalse(module.hasPending(), "no había nada que devolver: no hay nada que dejar pendiente");
    }

    /** Un viaje nuevo anota de cero: el pendiente del anterior ya no describe ningún reposo. */
    @Test
    void takingAgainForgetsThePending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        module.release(false, false);
        assertTrue(module.hasPending());
        module.take(true);
        assertFalse(module.hasPending());
    }

    @Test
    void forgetDropsTheNoteAndThePending() {
        BorrowedModule module = elytraFly();
        module.take(true);
        module.release(false, false);
        module.forget();
        assertFalse(module.hasPending());
        assertEquals(Action.NADA, module.release(false, true));
    }

    @Test
    void theInFlightStateIsTheDeclaredOne() {
        assertFalse(elytraFly().inFlight());
        assertTrue(elytraReplace().inFlight());
        assertEquals("elytra-fly", elytraFly().name());
        assertEquals("elytra-replace", elytraReplace().name());
    }
}
