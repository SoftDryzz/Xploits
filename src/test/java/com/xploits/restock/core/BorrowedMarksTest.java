package com.xploits.restock.core;

import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M5 (ruling R64, Minor 3): which filled shulker boxes the player carries count as the ones restock borrowed. With
 * {@code use-carried-shulkers} off only those are unpacked, so a box marked here by mistake is the player's own box
 * unpacked against the setting.
 */
class BorrowedMarksTest {
    private static final BorrowedShulkers.Kind RED = new BorrowedShulkers.Kind("minecraft:red_shulker_box", "");
    private static final BorrowedShulkers.Kind RED_NAMED =
        new BorrowedShulkers.Kind("minecraft:red_shulker_box", "Stone");
    private static final BorrowedShulkers.Kind BLUE = new BorrowedShulkers.Kind("minecraft:blue_shulker_box", "");
    private static final Pos ORIGIN = new Pos(0, 64, 0);
    private static final String DIM = "minecraft:overworld";

    @Test
    void perKindAtMostTheLedgerEntriesTheLowerSlotsFirst() {
        BorrowedShulkers ledger = ledger(RED, RED);
        assertEquals(Set.of(2, 5), BorrowedMarks.of(List.of(filled(2, RED), filled(5, RED), filled(30, RED)), ledger),
            "two entries: the two lower filled boxes, never the third (the player's own)");
        assertEquals(Set.of(7), BorrowedMarks.of(List.of(filled(7, RED)), ledger),
            "more entries than filled boxes: every filled box, and nothing more");
    }

    @Test
    void theLowerSlotsWinWhateverTheOrderGiven() {
        assertEquals(Set.of(2, 5), BorrowedMarks.of(List.of(filled(30, RED), filled(5, RED), filled(2, RED)),
            ledger(RED, RED)));
    }

    @Test
    void aBoxOfAKindTheLedgerDoesNotHoldIsNeverMarked() {
        assertEquals(Set.of(3), BorrowedMarks.of(List.of(filled(1, RED_NAMED), filled(2, BLUE), filled(3, RED)),
            ledger(RED, RED)), "same item under another name, or another item: another kind");
        assertEquals(Set.of(), BorrowedMarks.of(List.of(filled(1, RED), filled(2, BLUE)), new BorrowedShulkers()),
            "nothing borrowed: nothing marked");
    }

    @Test
    void eachKindHasItsOwnCount() {
        BorrowedShulkers ledger = ledger(RED, BLUE, BLUE);
        assertEquals(Set.of(1, 2, 4), BorrowedMarks.of(List.of(filled(1, RED), filled(2, BLUE), filled(3, RED),
            filled(4, BLUE)), ledger));
    }

    @Test
    void anEmptyBoxIsNeverMarkedAndTakesNoMarkFromAFilledOne() {
        // R50: the player's own boxes of a kind are presumed the empty ones first.
        assertEquals(Set.of(1), BorrowedMarks.of(List.of(empty(0, RED), filled(1, RED), filled(2, RED)), ledger(RED)));
        assertEquals(Set.of(1, 2), BorrowedMarks.of(List.of(empty(0, RED), filled(1, RED), filled(2, RED)),
            ledger(RED, RED)));
    }

    private static BorrowedShulkers ledger(BorrowedShulkers.Kind... kinds) {
        BorrowedShulkers ledger = new BorrowedShulkers();
        for (BorrowedShulkers.Kind k : kinds) ledger.borrow(new BorrowedShulkers.Borrowed(k, DIM, ORIGIN));
        return ledger;
    }

    private static BorrowedShulkers.Held filled(int slot, BorrowedShulkers.Kind kind) {
        return new BorrowedShulkers.Held(slot, kind, false);
    }

    private static BorrowedShulkers.Held empty(int slot, BorrowedShulkers.Kind kind) {
        return new BorrowedShulkers.Held(slot, kind, true);
    }
}
