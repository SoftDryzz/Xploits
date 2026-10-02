package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xploits.restock.core.PrintPause.Action.NONE;
import static com.xploits.restock.core.PrintPause.Action.SWITCH_OFF;
import static com.xploits.restock.core.PrintPause.Action.SWITCH_ON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restock spec §3 "Printer control": switched off for a trip only if it was printing; back on only if restock switched
 * it off and it is still off (the player's own toggle wins); a marker gives it back after the game closed mid-trip.
 * {@code printing} null: litematica-printer's print mode could not be read.
 */
class PrintPauseTest {
    @Test
    void aTripSwitchesItOffOnlyIfItWasPrinting() {
        assertEquals(SWITCH_OFF, PrintPause.atTripStart(true, true));
        assertEquals(NONE, PrintPause.atTripStart(true, false), "the player builds by hand, or switched it off");
        assertEquals(NONE, PrintPause.atTripStart(false, null), "not installed");
        assertEquals(NONE, PrintPause.atTripStart(true, null), "unreadable: never guessed");
    }

    @Test
    void theReturnSwitchesItBackOnlyIfStillOff() {
        assertEquals(SWITCH_ON, PrintPause.atReturn(true, false));
        assertEquals(NONE, PrintPause.atReturn(true, true), "the player switched it on during the trip");
        assertEquals(NONE, PrintPause.atReturn(false, false), "it was not printing when the trip started");
        assertEquals(NONE, PrintPause.atReturn(true, null));
    }

    @Test
    void theJoinGivesItBackOnlyWithTheMarkerAndOnlyIfStillOff() {
        List<String> marker = PrintPause.markerLines();
        assertEquals(SWITCH_ON, PrintPause.atJoin(marker, true, false));
        assertEquals(NONE, PrintPause.atJoin(marker, true, true));
        assertEquals(NONE, PrintPause.atJoin(marker, false, null), "uninstalled since");
        assertEquals(NONE, PrintPause.atJoin(List.of(), true, false), "no marker");
        assertEquals(NONE, PrintPause.atJoin(List.of("something else"), true, false), "not our marker");
    }

    @Test
    void aPrintModeThatCannotBeReadAtTheReturnIsSaid() {
        // Deferred L60: restock switched it off, and at the return it cannot tell whether it is still off.
        assertTrue(PrintPause.unknownAtReturn(true, null));
        assertFalse(PrintPause.unknownAtReturn(true, false), "switched back on");
        assertFalse(PrintPause.unknownAtReturn(true, true), "switched on by the player: said as such");
        assertFalse(PrintPause.unknownAtReturn(false, null), "restock did not switch it off");
    }

    @Test
    void aJoinThatCannotGiveItBackSaysSo() {
        // Deferred L37: the marker is on disk, litematica-printer is installed and not known to print.
        List<String> marker = PrintPause.markerLines();
        assertTrue(PrintPause.leftOffAtJoin(marker, true, null), "its print mode cannot be read");
        assertTrue(PrintPause.leftOffAtJoin(List.of("unreadable"), true, false), "the marker cannot be read, and it is off");
        assertFalse(PrintPause.leftOffAtJoin(marker, true, false), "the join switches it on (a switch that fails is said too)");
        assertFalse(PrintPause.leftOffAtJoin(marker, true, true), "it prints");
        assertFalse(PrintPause.leftOffAtJoin(List.of("unreadable"), true, true), "it prints");
        assertFalse(PrintPause.leftOffAtJoin(marker, false, null), "uninstalled since");
        assertFalse(PrintPause.leftOffAtJoin(List.of(), true, null), "no marker");
    }

    @Test
    void onlyLeavingTheWorldKeepsTheMarker() {
        assertTrue(PrintPause.keepMarker(RestockReason.LEFT));
        for (RestockReason r : List.of(RestockReason.PLAYER_NEAR, RestockReason.MODULE_OFF, RestockReason.PLAYER_MOVED,
            RestockReason.NO_PATH, RestockReason.DIED)) {
            assertFalse(PrintPause.keepMarker(r), r.name());
        }
    }

    @Test
    void theMarkerIsOneKnownLine() {
        assertTrue(PrintPause.isMarker(PrintPause.markerLines()));
        assertEquals(List.of("xploits-restock-printer-paused 1"), PrintPause.markerLines());
        assertFalse(PrintPause.isMarker(List.of("xploits-restock-printer-paused 1", "")));
    }
}
