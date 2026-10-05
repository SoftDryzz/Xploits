package com.xploits.restock;

import com.xploits.restock.core.ShulkersLeft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a stop says of the shulker box an unpack had out (restock spec §3 "never a shulker left behind silently"; I1,
 * M17; ruling R66): from what the world shows, and when it cannot be read — no world, or the world of another
 * dimension.
 */
class UnpackDriverTest {
    @Test
    void whatCannotBeReadIsUncheckedOnlyOnceABoxWentOut() {
        assertEquals(ShulkersLeft.NONE, UnpackDriver.unread(false, false), "no place went out: nothing to say");
        assertEquals(ShulkersLeft.UNCHECKED, UnpackDriver.unread(true, false));
        assertEquals(ShulkersLeft.UNCHECKED, UnpackDriver.unread(false, true), "a box was broken");
        assertEquals(ShulkersLeft.UNCHECKED, UnpackDriver.unread(true, true));
    }

    @Test
    void aStandingBoxIsNeverSaidUncheckedForAPlaceNotSeenYet() {
        // A place that went out and was not seen yet, left over from the last tick at a stop between ticks: the box
        // stands, and that is all there is to say.
        assertEquals(new ShulkersLeft(1, 2, -1, false, false, 0),
            UnpackDriver.said(1, 2, false, -1, false, true, false));
        assertEquals(new ShulkersLeft(1, 2, -1, false, false, 0),
            UnpackDriver.said(1, 2, false, -1, true, true, false), "a second box out of the inventory, one standing");
    }

    @Test
    void withNothingStandingAPlaceNotSeenYetOrABoxGoneUnseenIsUnchecked() {
        assertEquals(ShulkersLeft.UNCHECKED, UnpackDriver.said(0, -1, false, -1, false, true, false),
            "I1: the server has not answered the place yet");
        assertEquals(ShulkersLeft.UNCHECKED, UnpackDriver.said(0, -1, false, -1, true, false, false),
            "I1: fewer boxes carried, none standing, none broken");
        assertEquals(ShulkersLeft.NONE, UnpackDriver.said(0, -1, false, -1, false, false, false));
    }

    @Test
    void aBrokenBoxLiesOnTheGroundIsGoneOrIsBack() {
        assertEquals(new ShulkersLeft(0, -1, 3, false, false, 0),
            UnpackDriver.said(0, -1, true, 3, true, false, false), "on the ground, 3 blocks away");
        assertEquals(new ShulkersLeft(0, -1, -1, true, false, 0),
            UnpackDriver.said(0, -1, true, -1, true, false, false), "not seen and not carried: gone");
        assertEquals(ShulkersLeft.NONE, UnpackDriver.said(0, -1, true, -1, false, false, false),
            "picked up in the very tick of the stop: neither on the ground nor missing");
    }

    @Test
    void justBrokenIsSaidOnlyBesideAStandingBox() {
        assertTrue(UnpackDriver.said(1, 1, false, -1, false, false, true).breaking());
        assertFalse(UnpackDriver.said(1, 1, false, -1, false, false, false).breaking());
        assertFalse(UnpackDriver.said(0, -1, false, -1, false, false, true).breaking());
    }
}
