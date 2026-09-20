package com.xploits.kitrequester.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las carreras de {@code EnderDepositor} que llevaron tres rondas sin cerrarse (spec §6.1): un
 * candidato ajeno antes de {@code start()}, uno que llega entre {@code start()} y la pantalla, uno
 * caducado, y uno de un tipo que no abre contenedor.
 */
class CandidateTrackerTest {
    private static final long TIMEOUT_MS = 5_000;
    private static final long POS_A = 100L;
    private static final long POS_B = 200L;

    @Test
    void freshCandidateBlocksStart() {
        // Un candidato ajeno anotado justo antes de que EnderDepositor.start() intente empezar:
        // la pantalla que lo respalda puede no haber llegado todavía.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        tracker.expire(1_000);
        assertTrue(tracker.blocksStart(1_000), "un candidato vigente debe negarse a empezar");
    }

    @Test
    void noInteractionAtAllDoesNotBlockStart() {
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        assertFalse(tracker.blocksStart(1_000));
    }

    @Test
    void expiredCandidateNoLongerBlocksStart() {
        // Un candidato sin consumir -pantalla denegada, paquete perdido- no debe quedarse pendiente
        // para siempre.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        long later = 1_000 + TIMEOUT_MS + 1;
        tracker.expire(later);
        assertFalse(tracker.blocksStart(later), "pasado el timeout, el candidato ya no cuenta");
        assertFalse(tracker.matches(POS_A), "tampoco puede seguir emparejando una posición");
    }

    @Test
    void candidateExactlyAtTheTimeoutBoundaryIsStillFresh() {
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        long boundary = 1_000 + TIMEOUT_MS;
        tracker.expire(boundary);
        assertTrue(tracker.blocksStart(boundary), "la caducidad es estrictamente mayor que, no igual");
    }

    @Test
    void nonContainerBlockTypeIsNeverRecordedAsCandidate() {
        // Colocar un bloque, abrir una puerta, o los BlockUtils.place de surround/auto-trap no
        // deben poder abortar un depósito en curso ni bloquear el siguiente (spec §6.1, punto 2).
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, false, 1_000);
        assertFalse(tracker.blocksStart(1_000), "un tipo que no abre contenedor no debe anotarse");
        assertFalse(tracker.matches(POS_A));
    }

    @Test
    void ownInteractionMatchesItsOwnPosition() {
        // Lo que hace start() consigo mismo: anota su propio BlockPos como candidato.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        assertTrue(tracker.matches(POS_A));
        assertFalse(tracker.matches(POS_B));
    }

    @Test
    void foreignInteractionBetweenStartAndItsOwnScreenOverwritesTheCandidate() {
        // El orden que llevaba tres rondas abierto: start() ya mandó su interact y anotó su propio
        // candidato (POS_A), pero antes de que llegue SU pantalla, el jugador abre otra cosa
        // (POS_B). matches(POS_A) debe dejar de cumplirse: la pantalla que llegue después, si se
        // acepta solo por candidato, sería la ajena, no la del depositor.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        assertTrue(tracker.matches(POS_A));

        tracker.noteBlockInteraction(POS_B, true, 1_200);
        assertFalse(tracker.matches(POS_A), "la interacción ajena posterior pisa el candidato propio");
        assertTrue(tracker.matches(POS_B));
    }

    @Test
    void foreignInteractionBeforeStartIsSeenAsPending() {
        // El orden que ya cubrían rondas anteriores: una interacción ajena (POS_B) anotada antes de
        // que el depositor intente empezar. start() debe negarse (blocksStart), y si se ignorara esa
        // negativa, el candidato seguiría siendo el ajeno, nunca el del ender chest (POS_A).
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_B, true, 1_000);
        assertTrue(tracker.blocksStart(1_000));
        assertFalse(tracker.matches(POS_A));
    }

    @Test
    void freshEntityInteractionBlocksStartWithNoPositionToCompare() {
        // Vagoneta o barca con cofre: son entidades, InteractBlockEvent nunca las ve (spec §6.1,
        // tercera corrección). No hay BlockPos que anotar, pero start() debe negarse igual.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteEntityInteraction(1_000);
        assertTrue(tracker.blocksStart(1_000));
    }

    @Test
    void expiredEntityInteractionNoLongerBlocksStart() {
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteEntityInteraction(1_000);
        long later = 1_000 + TIMEOUT_MS + 1;
        assertFalse(tracker.blocksStart(later));
    }

    @Test
    void clearForgetsTheBlockCandidateButNotTheEntityMark() {
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        tracker.noteEntityInteraction(1_000);
        tracker.clear();

        assertFalse(tracker.matches(POS_A), "clear() olvida el candidato de bloque");
        assertTrue(tracker.blocksStart(1_000), "la marca de entidad caduca sola con el tiempo, no con clear()");
    }
}
