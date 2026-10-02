package com.xploits.restock.core;

import java.util.List;

/**
 * Restock's hold on litematica-printer's print mode (restock spec §3 "Printer control"): switched off for a trip only if
 * it was printing, switched back on at the return only if restock switched it off and it is still off — the player's own
 * toggle during the trip wins. The marker file says "restock holds it off" for as long as that is true: a trip that ends
 * by leaving the world (or a game that closes mid-trip) keeps it, and the next world join switches printing back on if
 * it is still off; any other stop leaves the printer off on purpose (the player is away from the build) and deletes it.
 * {@code printing} is null when the print mode cannot be read: nothing is ever guessed.
 */
public final class PrintPause {
    public enum Action { NONE, SWITCH_OFF, SWITCH_ON }

    /** The marker file's one line. */
    public static final String MARKER = "xploits-restock-printer-paused 1";

    private PrintPause() {
    }

    public static Action atTripStart(boolean installed, Boolean printing) {
        return installed && Boolean.TRUE.equals(printing) ? Action.SWITCH_OFF : Action.NONE;
    }

    public static Action atReturn(boolean pausedByUs, Boolean printing) {
        return pausedByUs && Boolean.FALSE.equals(printing) ? Action.SWITCH_ON : Action.NONE;
    }

    public static Action atJoin(List<String> marker, boolean installed, Boolean printing) {
        return isMarker(marker) && installed && Boolean.FALSE.equals(printing) ? Action.SWITCH_ON : Action.NONE;
    }

    /** At a stop while restock holds the printer off: only leaving the world keeps the marker for the join. */
    public static boolean keepMarker(RestockReason reason) {
        return reason == RestockReason.LEFT;
    }

    public static List<String> markerLines() {
        return List.of(MARKER);
    }

    public static boolean isMarker(List<String> lines) {
        return lines.equals(List.of(MARKER));
    }
}
