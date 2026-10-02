package com.xploits.restock.core;

import com.xploits.printer.core.Point;
import com.xploits.printer.core.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restock spec §2 phase B, owner ruling R44: the boxes restock carried away, given back to their container once empty. */
class BorrowedShulkersTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String BOX = "minecraft:shulker_box";
    private static final BorrowedShulkers.Kind PLAIN = new BorrowedShulkers.Kind(BOX, "");
    private static final BorrowedShulkers.Kind NAMED = new BorrowedShulkers.Kind(BOX, "stone");
    private static final Pos A = new Pos(10, 64, 0);
    private static final Pos B = new Pos(-4, 64, 0);

    private static BorrowedShulkers ledger() {
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        l.borrow(new BorrowedShulkers.Borrowed(NAMED, OVERWORLD, B));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, NETHER, new Pos(1, 64, 0)));
        return l;
    }

    private static BorrowedShulkers.Held held(int slot, BorrowedShulkers.Kind kind, boolean empty) {
        return new BorrowedShulkers.Held(slot, kind, empty);
    }

    @Test
    void eachContainerGetsBackOnlyItsOwnEmptyBoxes() {
        List<BorrowedShulkers.Held> carried = List.of(held(60, PLAIN, true), held(61, PLAIN, true), held(62, PLAIN, false),
            held(63, NAMED, true));
        assertEquals(List.of(new TakePlan.Slot(60, BOX, 1, 5), new TakePlan.Slot(61, BOX, 1, 5)),
            ledger().toReturn(OVERWORLD, A, carried, 5));
        assertEquals(List.of(new TakePlan.Slot(63, BOX, 1, 5)), ledger().toReturn(OVERWORLD, B, carried, 5));
        assertEquals(List.of(), ledger().toReturn(NETHER, A, carried, 5),
            "the same position in another dimension is another container");
        assertEquals(List.of(new TakePlan.Slot(60, BOX, 1, 0), new TakePlan.Slot(61, BOX, 1, 0)),
            ledger().toReturn(OVERWORLD, A, carried, 0), "a full container: TakePlan keeps them");
    }

    @Test
    void neverMoreThanWereBorrowedNorOneThatStillHoldsItems() {
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        assertEquals(List.of(new TakePlan.Slot(61, BOX, 1, 3)), l.toReturn(OVERWORLD, A,
            List.of(held(60, PLAIN, false), held(61, PLAIN, true), held(62, PLAIN, true)), 3),
            "owner ruling R44: only an empty one, and one per entry");
    }

    @Test
    void theLastTripGoesToTheNearestOriginWithAnEmptyBoxToTakeBack() {
        // From (0.5, 64, 0.5): B's centre is 4 away, A's 10; the nether entry is nearer than both and never counts.
        Point from = new Point(0.5, 64, 0.5);
        List<BorrowedShulkers.Held> both = List.of(held(1, PLAIN, true), held(2, NAMED, true));
        assertEquals(Optional.of(B), ledger().lastTripOrigin(OVERWORLD, from, both, Set.of()));
        assertEquals(Optional.of(A), ledger().lastTripOrigin(OVERWORLD, from, both, Set.of(B)),
            "a container a last trip already went to is skipped");
        assertEquals(Optional.of(A), ledger().lastTripOrigin(OVERWORLD, from,
            List.of(held(1, PLAIN, true), held(2, NAMED, false)), Set.of()), "B's box still holds items");
        assertEquals(Optional.empty(), ledger().lastTripOrigin(OVERWORLD, from, List.of(held(1, PLAIN, false)), Set.of()));
        assertEquals(Optional.empty(), new BorrowedShulkers().lastTripOrigin(OVERWORLD, from, both, Set.of()));
    }

    @Test
    void givingOneBackRemovesOneEntryOfThatKindFromThatContainer() {
        BorrowedShulkers l = ledger();
        assertTrue(l.giveBack(OVERWORLD, A, PLAIN));
        assertEquals(3, l.count());
        assertFalse(l.giveBack(OVERWORLD, A, new BorrowedShulkers.Kind(BOX, "other")), "a name it did not borrow");
        assertFalse(l.giveBack(OVERWORLD, B, PLAIN), "B lent a named one");
        assertTrue(l.isBorrowed(NAMED));
        assertFalse(l.isBorrowed(new BorrowedShulkers.Kind("minecraft:red_shulker_box", "")));
        assertFalse(l.isEmpty());
    }

    @Test
    void trimForgetsTheNewestOfAKindNoLongerCarried() {
        BorrowedShulkers l = new BorrowedShulkers();
        Pos c = new Pos(3, 64, 3);
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, c));
        l.borrow(new BorrowedShulkers.Borrowed(NAMED, OVERWORLD, B));
        l.trim(Map.of(PLAIN, 1, NAMED, 1));
        assertEquals(2, l.count());
        assertTrue(l.giveBack(OVERWORLD, A, PLAIN), "the older one is kept");
        assertFalse(l.giveBack(OVERWORLD, c, PLAIN), "the newest went");
        l.trim(Map.of());
        assertTrue(l.isEmpty(), "nothing carried: nothing borrowed");
    }

    @Test
    void theBorrowedBoxesCarriedAreTheFewerOfEntriesAndBoxes() {
        // Three plain entries (two here, one in the nether) and one named.
        assertEquals(1, ledger().carried(Map.of(PLAIN, 1)));
        assertEquals(4, ledger().carried(Map.of(PLAIN, 5, NAMED, 1)));
        assertEquals(0, ledger().carried(Map.of()));
    }

    @Test
    void anEntryNeverPrintsItsOrigin() {
        String printed = new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, new Pos(12345, 67, -6789)).toString();
        assertFalse(printed.contains("12345") || printed.contains("6789"), printed);
        assertTrue(printed.contains(HiddenPositions.HIDDEN) && printed.contains(OVERWORLD), printed);
    }
}
