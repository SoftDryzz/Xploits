package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §5.6 and §4 PendingBlocks; spike S7; Review Focus 2. */
class PendingBlocksTest {
    private static final Pos A = new Pos(0, 0, 3);
    private static final String STONE = "minecraft:stone";

    private static PendingBlocks pending() {
        return new PendingBlocks(PrinterLimits.DEFAULTS);
    }

    @Test
    void theFirstWindowAlreadyCoversThePing() {
        assertEquals(4, pending().windowTicks(0), "lock-step: the minimum");
        assertEquals(12, pending().windowTicks(6), "300 ms ping = 6 ticks each round trip: twice that");
    }

    @Test
    void theWindowFollowsTheSlowestRecentAnswer() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        p.acknowledged(1, 13);
        assertEquals(6, p.windowTicks(0));
        p.sent(new Pos(1, 0, 3), STONE, 2, 20);
        p.settle(25, 0, pos -> pos.equals(new Pos(1, 0, 3)));
        assertEquals(10, p.windowTicks(0));
        assertEquals(10, p.windowTicks(2), "the ping only counts when it is the larger");
    }

    @Test
    void onlyTheLastEightAnswersCount() {
        PendingBlocks p = pending();
        p.sent(new Pos(0, 0, 0), STONE, 1, 0);
        p.acknowledged(1, 9);
        for (int i = 1; i <= 8; i++) {
            p.sent(new Pos(i, 0, 0), STONE, i + 1, 100 * i);
            p.acknowledged(i + 1, 100 * i + 1);
        }
        assertEquals(4, p.windowTicks(0), "the 9-tick answer has left the last eight");
    }

    @Test
    void aBlockTheWorldShowsIsPlaced() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        assertTrue(p.pending(A));
        assertEquals(List.of(new PendingBlocks.Settled(A, STONE, PendingBlocks.Outcome.PLACED)), p.settle(11, 0, pos -> true));
        assertFalse(p.pending(A));
        assertEquals(Set.of(), p.inFlight());
        assertEquals(0, p.failures(A));
    }

    @Test
    void anAnswerWithoutTheBlockIsARefusal() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        p.acknowledged(1, 12);
        assertEquals(List.of(new PendingBlocks.Settled(A, STONE, PendingBlocks.Outcome.FAILED)), p.settle(12, 0, pos -> false));
        assertEquals(1, p.failures(A));
        assertFalse(p.pending(A));
    }

    @Test
    void anAcknowledgementCoversEveryEarlierSequence() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 4, 10);
        p.sent(new Pos(1, 0, 3), STONE, 5, 11);
        p.acknowledged(5, 13);
        assertEquals(2, p.settle(13, 0, pos -> false).size());
    }

    @Test
    void noAnswerWithinTheWindowIsAnExpiry() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        assertEquals(List.of(), p.settle(14, 0, pos -> false), "4 ticks: still within the window");
        assertEquals(List.of(new PendingBlocks.Settled(A, STONE, PendingBlocks.Outcome.EXPIRED)), p.settle(15, 0, pos -> false));
        assertEquals(1, p.failures(A));
    }

    @Test
    void aPositionIsSkippedAfterKFailures() {
        PendingBlocks p = pending();
        for (int i = 1; i <= 3; i++) {
            p.sent(A, STONE, i, 10 * i);
            p.acknowledged(i, 10 * i + 1);
            p.settle(10 * i + 1, 0, pos -> false);
            assertEquals(i == 3, p.skipped(A));
        }
        assertEquals(Set.of(A), p.skipped());
    }

    @Test
    void inventoryDropsGoFirstToPlacedEntries() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        p.sent(new Pos(1, 0, 3), STONE, 2, 11);
        p.settle(12, 0, pos -> pos.equals(new Pos(1, 0, 3)));
        assertEquals(Map.of(STONE, 1), p.placedNotSynced());
        p.inventoryDrop(STONE, 1);
        assertEquals(Map.of(), p.placedNotSynced(), "the drop is the placed block's");
        assertEquals(Map.of(), p.syncedNotPlaced());
        p.inventoryDrop(STONE, 1);
        assertEquals(Map.of(STONE, 1), p.syncedNotPlaced());
        p.inventoryDrop(STONE, 5);
        assertEquals(Map.of(STONE, 1), p.syncedNotPlaced(), "drops with no entry left are not ours");
        assertFalse(p.idle());
    }

    @Test
    void anEntryLeavesOnceBothAnswersArrived() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        p.settle(11, 0, pos -> true);
        p.inventoryDrop(STONE, 1);
        p.settle(12, 0, pos -> true);
        assertTrue(p.idle());
    }

    @Test
    void anEntryBothAcknowledgedAndShownIsPlacedNotRefused() {
        PendingBlocks p = pending();
        p.sent(A, STONE, 1, 10);
        p.acknowledged(1, 12);
        assertEquals(List.of(new PendingBlocks.Settled(A, STONE, PendingBlocks.Outcome.PLACED)), p.settle(12, 0, pos -> true));
        assertEquals(0, p.failures(A));
    }

    private static final Predicate<Pos> NOT_PLACED = pos -> false;
    private static final Predicate<Pos> STILL_THERE = pos -> true;
    private static final Predicate<Pos> GONE = pos -> false;

    private static PendingBlocks.Settled dug(PendingBlocks.Outcome outcome) {
        return new PendingBlocks.Settled(A, PendingBlocks.DIG, outcome);
    }

    @Test
    void aDigIsSettledByTheWorldUpdate() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        assertEquals(List.of(), p.settle(11, 0, NOT_PLACED, STILL_THERE));
        assertEquals(Set.of(A), p.digging());
        assertFalse(p.idle());
        assertEquals(List.of(dug(PendingBlocks.Outcome.DUG)), p.settle(12, 0, NOT_PLACED, GONE));
        assertEquals(Set.of(), p.digging());
        assertEquals(0, p.failures(A));
        assertTrue(p.idle());
        assertTrue(dug(PendingBlocks.Outcome.DUG).dig());
    }

    @Test
    void anAcknowledgedDigWithTheBlockStillThereStaysInFlight() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        p.acknowledged(1, 11);
        assertEquals(List.of(), p.settle(11, 0, NOT_PLACED, STILL_THERE), "the server may still break it after the ack");
        assertEquals(Set.of(A), p.digging());
        assertEquals(0, p.failures(A));
    }

    @Test
    void theBlockVanishingAfterTheAcknowledgementIsDugWithNoFailure() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        p.acknowledged(1, 11);
        assertEquals(List.of(), p.settle(12, 0, NOT_PLACED, STILL_THERE));
        assertEquals(List.of(dug(PendingBlocks.Outcome.DUG)), p.settle(14, 0, NOT_PLACED, GONE));
        assertEquals(0, p.failures(A));
    }

    @Test
    void anAcknowledgedDigStillThereAfterItsWindowExpiresAndCounts() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        p.acknowledged(1, 11);
        assertEquals(List.of(), p.settle(14, 0, NOT_PLACED, STILL_THERE));
        assertEquals(List.of(dug(PendingBlocks.Outcome.EXPIRED)), p.settle(15, 0, NOT_PLACED, STILL_THERE));
        assertEquals(1, p.failures(A));
    }

    @Test
    void theGraceExtendsTheDigWindow() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 20);
        assertEquals(List.of(), p.settle(10 + 4 + 10, 0, NOT_PLACED, STILL_THERE), "window 4 + 10: grace 20 not used up");
        assertEquals(List.of(), p.settle(10 + 4 + 20, 0, NOT_PLACED, STILL_THERE));
        assertEquals(List.of(dug(PendingBlocks.Outcome.EXPIRED)), p.settle(10 + 4 + 21, 0, NOT_PLACED, STILL_THERE));
    }

    @Test
    void theThreeArgumentSettleReadsEveryDugBlockAsStillThere() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        assertEquals(List.of(), p.settle(12, 0, pos -> false), "it cannot see a dug block go");
        assertEquals(List.of(dug(PendingBlocks.Outcome.EXPIRED)), p.settle(15, 0, pos -> false));
        assertEquals(1, p.failures(A));
    }

    @Test
    void aPlacementWithoutAMaterialIsRejected() {
        PendingBlocks p = pending();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> p.sent(A, "", 1, 1));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> p.sent(A, null, 1, 1));
    }

    @Test
    void aDigWithNoAnswerExpiresAfterTheWindow() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        assertEquals(List.of(), p.settle(14, 0, NOT_PLACED, STILL_THERE), "4 ticks: still within the window");
        assertEquals(Set.of(A), p.digging());
        assertEquals(List.of(dug(PendingBlocks.Outcome.EXPIRED)), p.settle(15, 0, NOT_PLACED, STILL_THERE));
        assertEquals(Set.of(), p.digging());
        assertEquals(1, p.failures(A));
    }

    @Test
    void aFailedDigCountsTowardsK() {
        PendingBlocks p = pending();
        for (int i = 1; i <= 3; i++) {
            p.digSent(A, i, 10 * i, 0);
            p.acknowledged(i, 10 * i + 1);
            p.settle(10 * i + 5, 0, NOT_PLACED, STILL_THERE);
            assertEquals(i == 3, p.skipped(A));
        }
    }

    @Test
    void aDigDoesNotShowAsAPlacementInFlight() {
        PendingBlocks p = pending();
        p.digSent(A, 1, 10, 0);
        assertFalse(p.pending(A));
        assertEquals(Set.of(), p.inFlight());
    }
}
