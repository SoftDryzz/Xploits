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
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, B));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, new Pos(3, 64, 3)));
        // Three borrowed, three carried (no own box): A lent one, so one goes back, and never the one still holding items.
        assertEquals(List.of(new TakePlan.Slot(61, BOX, 1, 3)), l.toReturn(OVERWORLD, A,
            List.of(held(60, PLAIN, false), held(61, PLAIN, true), held(62, PLAIN, true)), 3),
            "owner ruling R44: only an empty one, and one per entry");
    }

    @Test
    void thePlayersOwnEmptyBoxIsNeverHandedBackInPlaceOfABorrowedOneStillFilled() {
        // Owner ruling R50 (R44): two boxes of one kind are interchangeable, so the player's own count first.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        assertEquals(List.of(), l.toReturn(OVERWORLD, A, List.of(held(60, PLAIN, false), held(61, PLAIN, true)), 3),
            "one own empty box and the borrowed one still holds items");
        assertEquals(List.of(), l.toReturn(OVERWORLD, A, List.of(held(60, PLAIN, true), held(61, PLAIN, false)), 3));
    }

    @Test
    void twoBorrowedEmptyBoxesGoBackBesideAnOwnEmptyOne() {
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, A));
        assertEquals(2, l.toReturn(OVERWORLD, A,
            List.of(held(60, PLAIN, true), held(61, PLAIN, true), held(62, PLAIN, true)), 3).size());
    }

    @Test
    void aTieOfDistanceGoesToTheLowerX() {
        BorrowedShulkers l = new BorrowedShulkers();
        Pos east = new Pos(3, 64, 0);
        Pos west = new Pos(-3, 64, 0);
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, east));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, west));
        assertEquals(Optional.of(west), l.lastTripOrigin(OVERWORLD, new Point(0.5, 64.5, 0.5),
            List.of(held(1, PLAIN, true)), Set.of()));
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

    // --- ruling R70: a container visit's box moves, settled at its close ------------------------------------------

    private static BorrowedShulkers.Borrowed fromA(BorrowedShulkers.Kind kind) {
        return new BorrowedShulkers.Borrowed(kind, OVERWORLD, A);
    }

    /** A carry whose click packet left, as the session notes it: in the ledger, and in the visit. */
    private static void carried(BorrowedShulkers l, BorrowedShulkers.Visit v, BorrowedShulkers.Kind kind) {
        l.borrow(fromA(kind));
        v.borrowed(fromA(kind));
    }

    /** A give-back whose click packet left, as the session notes it: its entry gone, and in the visit. */
    private static void gaveBack(BorrowedShulkers l, BorrowedShulkers.Visit v, BorrowedShulkers.Kind kind) {
        assertTrue(l.giveBack(OVERWORLD, A, kind));
        v.gaveBack(fromA(kind));
    }

    @Test
    void aRefusedCarryAddsNothing() {
        // The server refused the click and its correction put the box back in the chest before the close.
        BorrowedShulkers l = new BorrowedShulkers();
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of());
        carried(l, v, PLAIN);
        l.settle(v, List.of());
        assertTrue(l.isEmpty());
    }

    @Test
    void thePlayersOwnBoxOfThatKindNeverBecomesBorrowed() {
        // The player carries an own empty plain box and a carry of a plain box is refused. Kept, the entry would make
        // the own box look borrowed (R50's count), and it would go to the chest on the next visit or a last trip.
        BorrowedShulkers l = new BorrowedShulkers();
        List<BorrowedShulkers.Held> own = List.of(held(3, PLAIN, true));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(own);
        carried(l, v, PLAIN);
        l.settle(v, own);
        l.trim(Map.of(PLAIN, 1));
        assertEquals(0, l.carried(Map.of(PLAIN, 1)));
        assertEquals(List.of(), l.toReturn(OVERWORLD, A, own, 27));
        assertEquals(Optional.empty(), l.lastTripOrigin(OVERWORLD, new Point(0, 64, 0), own, Set.of()));
    }

    @Test
    void anAcceptedCarryStays() {
        BorrowedShulkers l = new BorrowedShulkers();
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(3, PLAIN, true)));
        carried(l, v, PLAIN);
        l.settle(v, List.of(held(3, PLAIN, true), held(8, PLAIN, false)));
        assertEquals(1, l.count());
        assertEquals(1, l.carried(Map.of(PLAIN, 2)));
    }

    @Test
    void aBoxClickedAgainAfterItsCorrectionCountsOnce() {
        BorrowedShulkers l = new BorrowedShulkers();
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of());
        carried(l, v, PLAIN);
        carried(l, v, PLAIN);
        l.settle(v, List.of(held(8, PLAIN, false)));
        assertEquals(1, l.count());
    }

    @Test
    void aRefusalNeverTakesAnEarlierBorrowWithIt() {
        // A box borrowed on an earlier visit, carried and emptied since, and this visit's refused carry of that kind:
        // only this visit's note goes, so the earlier box still goes back on the next visit.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(fromA(PLAIN));
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, B));
        List<BorrowedShulkers.Held> carriedNow = List.of(held(0, PLAIN, true), held(1, PLAIN, true));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(carriedNow);
        carried(l, v, PLAIN);
        l.settle(v, carriedNow);
        assertEquals(2, l.count());
        assertEquals(List.of(new TakePlan.Slot(0, BOX, 1, 27)), l.toReturn(OVERWORLD, A, carriedNow, 27));
        assertTrue(l.giveBack(OVERWORLD, B, PLAIN), "another container's entry of that kind is never touched");
    }

    @Test
    void eachKindIsSettledOnItsOwn() {
        BorrowedShulkers l = new BorrowedShulkers();
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of());
        carried(l, v, PLAIN);
        carried(l, v, NAMED);
        l.settle(v, List.of(held(8, NAMED, false)));
        assertEquals(1, l.count());
        assertTrue(l.isBorrowed(NAMED));
        assertFalse(l.isBorrowed(PLAIN), "the refused plain carry left nothing");
    }

    @Test
    void aRefusedGiveBackKeepsItsEntryAndAnAcceptedOneDoesNot() {
        // Review Minor 1: a give-back counts once its box left the player.
        List<BorrowedShulkers.Held> before = List.of(held(0, PLAIN, true));
        BorrowedShulkers refused = new BorrowedShulkers();
        refused.borrow(fromA(PLAIN));
        BorrowedShulkers.Visit r = new BorrowedShulkers.Visit(before);
        gaveBack(refused, r, PLAIN);
        refused.settle(r, before);
        assertEquals(1, refused.count(), "the box is still carried: still borrowed");
        BorrowedShulkers accepted = new BorrowedShulkers();
        accepted.borrow(fromA(PLAIN));
        BorrowedShulkers.Visit a = new BorrowedShulkers.Visit(before);
        gaveBack(accepted, a, PLAIN);
        accepted.settle(a, List.of());
        assertTrue(accepted.isEmpty());
    }

    @Test
    void aGiveBackAndACarryOfOneKindInOneVisitAreToldApart() {
        // An empty borrowed box went back and a filled one of that kind came out: as many plain boxes as before, yet
        // both happened — the borrowed box is the filled one now.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(fromA(PLAIN));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(0, PLAIN, true)));
        gaveBack(l, v, PLAIN);
        carried(l, v, PLAIN);
        l.settle(v, List.of(held(8, PLAIN, false)));
        assertEquals(1, l.count());
        assertEquals(1, l.carried(Map.of(PLAIN, 1)));
    }

    @Test
    void aCarriedBoxThatCameBackIsSeenAsSoonAsItsAnswerArrives() {
        // The hardening: once the server put a carried box back, the visit carries nothing more.
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(3, PLAIN, true)));
        assertFalse(v.cameBack(List.of(held(3, PLAIN, true))), "nothing carried yet");
        v.borrowed(fromA(PLAIN));
        assertFalse(v.cameBack(List.of(held(3, PLAIN, true), held(8, PLAIN, false))),
            "the box shows: no answer, or the server took the click");
        assertTrue(v.cameBack(List.of(held(3, PLAIN, true))), "its correction put it back in the chest");
        assertTrue(v.cameBack(List.of(held(3, PLAIN, true), held(8, PLAIN, true))),
            "an empty box is not the one carried");
    }

    // --- ruling R71: inside the visit, and at the next screen's sync ------------------------------------------------

    @Test
    void aVisitWhoseCarryCameBackGivesNoBoxOfThatKindBack() {
        // The re-review's scenario A: the player carries an own empty plain box, the carry of a filled plain box is
        // refused and its correction arrives during the answer wait. Until the close the carry's entry stays in the
        // ledger, and R50's count alone would take the own box for the borrowed one.
        BorrowedShulkers l = new BorrowedShulkers();
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(3, PLAIN, true)));
        carried(l, v, PLAIN);
        List<BorrowedShulkers.Held> screen = List.of(held(57, PLAIN, true));
        assertEquals(List.of(new TakePlan.Slot(57, BOX, 1, 25)), l.toReturn(OVERWORLD, A, screen, 25),
            "R50's count alone hands the own box over");
        assertEquals(List.of(), l.toReturn(OVERWORLD, A, screen, 25, v));
        l.settle(v, List.of(held(3, PLAIN, true)));
        assertTrue(l.isEmpty());
    }

    @Test
    void aCarryThatArrivedOrOfAnotherKindWithholdsNothing() {
        // The control (scenario C): an accepted carry beside the own empty box gives nothing back either way, and keeps
        // its entry; an empty borrowed box of another kind still goes back beside a refused plain carry.
        BorrowedShulkers accepted = new BorrowedShulkers();
        BorrowedShulkers.Visit a = new BorrowedShulkers.Visit(List.of(held(3, PLAIN, true)));
        carried(accepted, a, PLAIN);
        List<BorrowedShulkers.Held> both = List.of(held(57, PLAIN, true), held(62, PLAIN, false));
        assertEquals(List.of(), accepted.toReturn(OVERWORLD, A, both, 25, a));
        accepted.settle(a, List.of(held(3, PLAIN, true), held(8, PLAIN, false)));
        assertEquals(1, accepted.count());
        BorrowedShulkers other = new BorrowedShulkers();
        other.borrow(fromA(NAMED));
        BorrowedShulkers.Visit r = new BorrowedShulkers.Visit(List.of(held(3, PLAIN, true), held(4, NAMED, true)));
        carried(other, r, PLAIN);
        assertEquals(List.of(new TakePlan.Slot(58, BOX, 1, 25)),
            other.toReturn(OVERWORLD, A, List.of(held(57, PLAIN, true), held(58, NAMED, true)), 25, r));
    }

    @Test
    void aRefusedGiveBackOfTheOwnBoxNeverBringsTheRefusedCarryBack() {
        // The re-review's scenario B: a give-back used up the refused carry's entry and was refused too. The settle
        // gives back entries before it forgets, so nothing outlives it.
        BorrowedShulkers l = new BorrowedShulkers();
        List<BorrowedShulkers.Held> own = List.of(held(3, PLAIN, true));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(own);
        carried(l, v, PLAIN);
        gaveBack(l, v, PLAIN);
        l.settle(v, own);
        assertTrue(l.isEmpty());
    }

    @Test
    void aGhostBoxIsForgottenOnceTheNextScreenBringsTheInventory() {
        // R70's residual: the server answered the carry after the answer wait, so the close saw the box (a ghost) and
        // kept the carry. The next container screen brings the inventory as the server has it.
        BorrowedShulkers l = new BorrowedShulkers();
        List<BorrowedShulkers.Held> own = List.of(held(3, PLAIN, true));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(own);
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(3, PLAIN, true), held(8, PLAIN, false)));
        assertEquals(1, l.count(), "the ghost looked arrived");
        l.recheck(kept, own);
        assertTrue(l.isEmpty());
        assertEquals(List.of(), l.toReturn(OVERWORLD, A, List.of(held(57, PLAIN, true)), 25), "the own box stays");
    }

    @Test
    void anUnpackSinceKeepsTheCarry() {
        // The borrowed box was unpacked at the build in between: empty now, still carried. Boxes are counted holding
        // items or not.
        BorrowedShulkers l = new BorrowedShulkers();
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(3, PLAIN, true)));
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(3, PLAIN, true), held(8, PLAIN, false)));
        l.recheck(kept, List.of(held(0, PLAIN, true), held(3, PLAIN, true)));
        assertEquals(1, l.count());
    }

    @Test
    void theRecheckOnlyForgetsTheKeptCarriesAndOnlyOnce() {
        // It runs every tick until that visit moves a box: what vanished is forgotten once; more boxes vanishing than
        // carries were kept forgets only those carries; a box back again brings nothing back; another container's
        // entry of that kind is never touched.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, B));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(2, PLAIN, true)));
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(2, PLAIN, true), held(8, PLAIN, false)));
        assertEquals(2, l.count());
        l.recheck(kept, List.of());
        assertEquals(1, l.count());
        l.recheck(kept, List.of());
        l.recheck(kept, List.of(held(2, PLAIN, true), held(8, PLAIN, false)));
        assertEquals(1, l.count());
        assertTrue(l.giveBack(OVERWORLD, B, PLAIN), "B's entry was never touched");
    }

    @Test
    void theRecheckForgetsOnlyTheKeptCarryBesideAnEarlierBorrowFromTheSameContainer() {
        // An earlier visit's borrow from A, emptied since and still carried, and the player's own empty box. This visit
        // at A carried twice: one refused, one kept on a ghost. At the next screen the ghost and the own box (put away
        // by hand) are both gone: only the one kept carry is forgotten, and only once.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(fromA(PLAIN));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(0, PLAIN, true), held(3, PLAIN, true)));
        carried(l, v, PLAIN);
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(0, PLAIN, true), held(3, PLAIN, true),
            held(8, PLAIN, false)));
        assertEquals(2, l.count(), "the earlier borrow and the carry that looked arrived");
        l.recheck(kept, List.of(held(0, PLAIN, true)));
        assertEquals(1, l.count());
        l.recheck(kept, List.of(held(0, PLAIN, true)));
        assertEquals(1, l.count(), "each vanished box forgets one carry, once");
        assertEquals(List.of(new TakePlan.Slot(54, BOX, 1, 27)),
            l.toReturn(OVERWORLD, A, List.of(held(54, PLAIN, true)), 27), "the earlier borrowed box still goes back");
    }

    // --- ruling R72 -------------------------------------------------------------------------------------------------

    @Test
    void theRecheckSaysHowManyEntriesItForgot() {
        // m1: a last trip's "returned" count must not take a forgotten entry for a box that went back.
        BorrowedShulkers l = new BorrowedShulkers();
        List<BorrowedShulkers.Held> own = List.of(held(3, PLAIN, true));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(own);
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(3, PLAIN, true), held(8, PLAIN, false)));
        assertEquals(1, l.recheck(kept, own));
        assertEquals(0, l.recheck(kept, own), "once");
    }

    @Test
    void anEntryTheTrimForgotFirstIsNotForgottenAgain() {
        // The idle trim may forget the ghost's entry before the next screen (no box of that kind carried): the recheck
        // then forgets nothing, and says so, so a last trip's count is not lowered twice.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(new BorrowedShulkers.Borrowed(NAMED, OVERWORLD, B));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of());
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(8, PLAIN, false), held(9, NAMED, true)));
        l.trim(Map.of(NAMED, 1));
        assertEquals(1, l.count(), "the trim forgot the ghost's entry");
        assertEquals(0, l.recheck(kept, List.of(held(9, NAMED, true))));
        assertEquals(1, l.count());
    }

    @Test
    void atRestocksNextScreenTheRecheckComesBeforeTheGiveBacks() {
        // The re-review's replay J2: a ghost kept at the last close; the next screen brings the inventory without it.
        // Listed before the recheck, the own empty box would go back in its place.
        BorrowedShulkers l = new BorrowedShulkers();
        List<BorrowedShulkers.Held> own = List.of(held(3, PLAIN, true));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(own);
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(3, PLAIN, true), held(8, PLAIN, false)));
        List<BorrowedShulkers.Held> screen = List.of(held(57, PLAIN, true));
        assertEquals(new BorrowedShulkers.AtScreen(List.of(), 1), l.atScreen(kept, null, OVERWORLD, A, screen, 25));
        assertTrue(l.isEmpty());
        assertEquals(new BorrowedShulkers.AtScreen(List.of(), 0), l.atScreen(kept, null, OVERWORLD, A, screen, 25));
    }

    @Test
    void onceTheVisitMovedABoxTheScreenNoLongerRechecks() {
        // The last visit's baseline holds only until this visit's first box move; its own moves are the visit's.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(fromA(PLAIN));
        BorrowedShulkers.Visit last = new BorrowedShulkers.Visit(List.of());
        carried(l, last, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(last, List.of(held(0, PLAIN, true), held(8, PLAIN, false)));
        BorrowedShulkers.Visit now = new BorrowedShulkers.Visit(List.of(held(0, PLAIN, true), held(8, PLAIN, false)));
        gaveBack(l, now, PLAIN);
        assertEquals(new BorrowedShulkers.AtScreen(List.of(), 0),
            l.atScreen(kept, now, OVERWORLD, A, List.of(held(62, PLAIN, false)), 25));
        assertEquals(1, l.count(), "the give-back took one entry, the recheck none");
    }

    @Test
    void aKeptCountNeverExceedsTheCarriesWhenMoreBoxesArrive() {
        // m3: one carry noted while two filled boxes arrived (one picked up): one carry is kept, not two, so the
        // recheck never takes the earlier borrow from the same container with it.
        BorrowedShulkers l = new BorrowedShulkers();
        l.borrow(fromA(PLAIN));
        BorrowedShulkers.Visit v = new BorrowedShulkers.Visit(List.of(held(0, PLAIN, true)));
        carried(l, v, PLAIN);
        BorrowedShulkers.Kept kept = l.settle(v, List.of(held(0, PLAIN, true), held(8, PLAIN, false),
            held(9, PLAIN, false)));
        assertEquals(2, l.count());
        assertEquals(1, l.recheck(kept, List.of(held(0, PLAIN, true))));
        assertEquals(1, l.count(), "the earlier borrow stays");
    }

    @Test
    void withNoVisitTheFiveArgumentGiveBackIsTheFourArgumentOne() {
        List<BorrowedShulkers.Held> carried = List.of(held(60, PLAIN, true), held(61, PLAIN, true));
        assertEquals(List.of(new TakePlan.Slot(60, BOX, 1, 5), new TakePlan.Slot(61, BOX, 1, 5)),
            ledger().toReturn(OVERWORLD, A, carried, 5, null));
    }

    @Test
    void anEntryNeverPrintsItsOrigin() {
        String printed = new BorrowedShulkers.Borrowed(PLAIN, OVERWORLD, new Pos(12345, 67, -6789)).toString();
        assertFalse(printed.contains("12345") || printed.contains("6789"), printed);
        assertTrue(printed.contains(HiddenPositions.HIDDEN) && printed.contains(OVERWORLD), printed);
    }
}
