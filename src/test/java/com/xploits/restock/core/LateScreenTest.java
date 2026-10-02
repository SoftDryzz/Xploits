package com.xploits.restock.core;

import org.junit.jupiter.api.Test;

import static com.xploits.restock.core.LateScreen.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Rulings R12, R28, R28b and deferred L79: the screen that answers restock's click only after the open gave up waiting
 * for it is closed — that one, by its syncId, and never another.
 */
class LateScreenTest {
    private static final int WINDOW = 100;
    private static final int LATE = 7;
    private static final int OTHER = 9;

    /** A click left and the open gave up waiting for its screen. */
    private static LateScreen afterAClick() {
        LateScreen l = new LateScreen(WINDOW);
        l.clicked();
        return l;
    }

    @Test
    void aScreenInTheWindowIsClosedOnceACloseIsAllowed() {
        LateScreen l = afterAClick();
        for (int i = 1; i <= 5; i++) assertEquals(NONE, l.tick(NONE, true, false, true), "tick " + i);
        assertEquals(NONE, l.tick(LATE, true, false, false), "the player is not standing still");
        assertEquals(LATE, l.tick(LATE, true, false, true));
        assertEquals(NONE, l.tick(LATE, true, false, true), "closed once");
    }

    @Test
    void aScreenThatCameInTheWindowIsClosedEvenAfterTheWindowRanOut() {
        // Deferred L79: it arrived while the player walked to the next source; the close needs the walk to end, which
        // takes longer than the window. It is remembered by its syncId and closed on arrival.
        LateScreen l = afterAClick();
        for (int i = 1; i <= 50; i++) l.tick(NONE, true, false, false);
        assertEquals(NONE, l.tick(LATE, true, false, false), "walking: no close");
        for (int i = 1; i <= 300; i++) assertEquals(NONE, l.tick(LATE, i < 20, false, false), "tick " + i);
        assertEquals(LATE, l.tick(LATE, false, false, true));
    }

    @Test
    void aScreenAfterTheWindowIsNeverClosed() {
        LateScreen l = afterAClick();
        for (int i = 1; i <= WINDOW - 1; i++) l.tick(NONE, true, false, true);
        LateScreen last = afterAClick();
        for (int i = 1; i <= WINDOW - 1; i++) last.tick(NONE, true, false, true);
        assertEquals(LATE, last.tick(LATE, true, false, true), "on the window's last tick it is still the answer");
        assertEquals(NONE, l.tick(NONE, true, false, true));
        assertEquals(NONE, l.tick(LATE, true, false, true), "one tick later it is a screen the player opened");
    }

    @Test
    void anotherScreenIsNeverClosed() {
        LateScreen replaced = afterAClick();
        replaced.tick(LATE, true, false, false);
        assertEquals(NONE, replaced.tick(OTHER, true, false, true), "the late one was replaced by another");
        assertEquals(NONE, replaced.tick(OTHER, true, false, true));
        LateScreen gone = afterAClick();
        gone.tick(LATE, true, false, false);
        assertEquals(NONE, gone.tick(NONE, true, false, true), "the server or the player closed it");
        assertEquals(NONE, gone.tick(OTHER, true, false, true), "and the next screen is the player's");
    }

    @Test
    void pausedTicksDoNotCountTowardsTheWindow() {
        LateScreen l = afterAClick();
        for (int i = 1; i <= 150; i++) assertEquals(NONE, l.tick(NONE, true, true, false));
        assertEquals(LATE, l.tick(LATE, true, false, true));
    }

    @Test
    void outOfTheServersRangeTheWaitEnds() {
        // The server closes a container's screen itself beyond canInteractWithBlockAt(pos, 4.0): no answer can come.
        LateScreen l = afterAClick();
        assertEquals(NONE, l.tick(NONE, false, false, true));
        assertEquals(NONE, l.tick(LATE, true, false, true));
    }

    @Test
    void anAnswerTheOpenAdoptedIsNotLate() {
        LateScreen l = afterAClick();
        l.adopted(LATE);
        assertEquals(NONE, l.tick(LATE, true, false, true));
        assertEquals(NONE, new LateScreen(WINDOW).tick(LATE, true, false, true), "no click, no late screen");
    }

    @Test
    void aWindowBelowOneTickIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LateScreen(0));
    }
}
