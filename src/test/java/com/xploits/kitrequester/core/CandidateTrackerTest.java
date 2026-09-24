package com.xploits.kitrequester.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code EnderDepositor} races that took three rounds to close (spec §6.1): an unrelated
 * candidate before {@code start()}, one that arrives between {@code start()} and the screen, an
 * expired one, and one of a type that does not open a container.
 */
class CandidateTrackerTest {
    private static final long TIMEOUT_MS = 5_000;
    private static final long POS_A = 100L;
    private static final long POS_B = 200L;

    @Test
    void freshCandidateBlocksStart() {
        // An unrelated candidate noted right before EnderDepositor.start() tries to begin: the
        // screen behind it may not have arrived yet.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        tracker.expire(1_000);
        assertTrue(tracker.blocksStart(1_000), "a current candidate must refuse to start");
    }

    @Test
    void noInteractionAtAllDoesNotBlockStart() {
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        assertFalse(tracker.blocksStart(1_000));
    }

    @Test
    void expiredCandidateNoLongerBlocksStart() {
        // An unconsumed candidate -screen denied, packet lost- must not stay pending forever.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        long later = 1_000 + TIMEOUT_MS + 1;
        tracker.expire(later);
        assertFalse(tracker.blocksStart(later), "past the timeout, the candidate no longer counts");
        assertFalse(tracker.matches(POS_A), "nor can it still match a position");
    }

    @Test
    void candidateExactlyAtTheTimeoutBoundaryIsStillFresh() {
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        long boundary = 1_000 + TIMEOUT_MS;
        tracker.expire(boundary);
        assertTrue(tracker.blocksStart(boundary), "expiry is strictly greater than, not equal to");
    }

    @Test
    void nonContainerBlockTypeIsNeverRecordedAsCandidate() {
        // Placing a block, opening a door, or the BlockUtils.place calls of surround/auto-trap must
        // not be able to abort a deposit in progress or block the next one (spec §6.1, point 2).
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, false, 1_000);
        assertFalse(tracker.blocksStart(1_000), "a type that does not open a container must not be noted");
        assertFalse(tracker.matches(POS_A));
    }

    @Test
    void ownInteractionMatchesItsOwnPosition() {
        // What start() does with itself: it notes its own BlockPos as a candidate.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        assertTrue(tracker.matches(POS_A));
        assertFalse(tracker.matches(POS_B));
    }

    @Test
    void foreignInteractionBetweenStartAndItsOwnScreenOverwritesTheCandidate() {
        // The order that took three rounds open: start() already sent its interact and noted its own
        // candidate (POS_A), but before ITS screen arrives, the player opens something else (POS_B).
        // matches(POS_A) must stop holding: the screen that arrives afterwards, if accepted purely by
        // candidate, would be the unrelated one, not the depositor's.
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_A, true, 1_000);
        assertTrue(tracker.matches(POS_A));

        tracker.noteBlockInteraction(POS_B, true, 1_200);
        assertFalse(tracker.matches(POS_A), "the later unrelated interaction overwrites the own candidate");
        assertTrue(tracker.matches(POS_B));
    }

    @Test
    void foreignInteractionBeforeStartIsSeenAsPending() {
        // The order already covered by earlier rounds: an unrelated interaction (POS_B) noted before
        // the depositor tries to start. start() must refuse (blocksStart), and if that refusal were
        // ignored, the candidate would still be the unrelated one, never the ender chest's (POS_A).
        CandidateTracker tracker = new CandidateTracker(TIMEOUT_MS);
        tracker.noteBlockInteraction(POS_B, true, 1_000);
        assertTrue(tracker.blocksStart(1_000));
        assertFalse(tracker.matches(POS_A));
    }

    @Test
    void freshEntityInteractionBlocksStartWithNoPositionToCompare() {
        // Minecart or boat with chest: they are entities, InteractBlockEvent never sees them (spec
        // §6.1, third fix). There is no BlockPos to note, but start() must refuse all the same.
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

        assertFalse(tracker.matches(POS_A), "clear() forgets the block candidate");
        assertTrue(tracker.blocksStart(1_000), "the entity mark expires on its own over time, not with clear()");
    }
}
