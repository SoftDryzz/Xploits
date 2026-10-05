package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Final review m1: "nowhere" is said once for a material, not again by the retry a minute after the trip said it. */
class NowhereNotesTest {
    private static final String STONE = "minecraft:stone";

    @Test
    void aTripThatLeavesNoSourceSaysItAndTheRetryStaysSilent() {
        NowhereNotes notes = new NowhereNotes();
        assertEquals(Optional.of(RestockText.NOWHERE_AFTER_TRIP), notes.afterTrip(STONE, false, false));
        assertFalse(notes.firstTime(STONE), "the retry's own 'nowhere' is not said again");
        assertEquals(Set.of(STONE), notes.materials());
    }

    @Test
    void theBoxesTextIsChosenWhenAContainerWasPassedOverForHoldingItOnlyInBoxes() {
        NowhereNotes notes = new NowhereNotes();
        assertEquals(Optional.of(RestockText.NOWHERE_AFTER_TRIP_BOXES), notes.afterTrip(STONE, false, true));
        assertFalse(notes.firstTime(STONE));
    }

    @Test
    void aTripWithAnotherSourceLeftSaysNothingAndNotesNothing() {
        NowhereNotes notes = new NowhereNotes();
        assertEquals(Optional.empty(), notes.afterTrip(STONE, true, false));
        assertTrue(notes.firstTime(STONE), "the first real 'nowhere' is still to be said");
    }

    @Test
    void newSourcesLetItBeSaidAgain() {
        NowhereNotes notes = new NowhereNotes();
        notes.afterTrip(STONE, false, false);
        notes.clear();
        assertTrue(notes.firstTime(STONE));
        assertFalse(notes.firstTime(STONE));
    }
}
