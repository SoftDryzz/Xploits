package com.xploits.shared.core;

import com.xploits.shared.core.ChatLogMask.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatLogMaskTest {
    @Test
    void masksTwoAndThreeNumberPositions() {
        assertEquals("[Meteor] Trip launched: 3 waypoints to ***, ***.",
            ChatLogMask.mask("[Meteor] Trip launched: 3 waypoints to -1234567, 7654321."));
        assertEquals("Netherite Leggings found  at ***, ***, ***",
            ChatLogMask.mask("Netherite Leggings found  at -123456, 64, 654321"));
        assertEquals("[Baritone] > goal *** ***", ChatLogMask.mask("[Baritone] > goal -1234567 -7654321"));
        assertEquals("tp *** *** ***", ChatLogMask.mask("tp 1234.5 70 -5678.25"));
    }

    @Test
    void masksNamedAxesAndAtForm() {
        assertEquals("[Baritone] Goal: GoalXZ{x=***,z=***}",
            ChatLogMask.mask("[Baritone] Goal: GoalXZ{x=-1234567,z=-7654321}"));
        assertEquals("GoalBlock{x=***,y=***,z=***}", ChatLogMask.mask("GoalBlock{x=12,y=64,z=-3}"));
        assertEquals("pos X: *** Z: ***", ChatLogMask.mask("pos X: 1234 Z: -99"));
        assertEquals("at @***,***,***", ChatLogMask.mask("at @100,64,-200"));
    }

    @Test
    void leavesOrdinaryNumbersAlone() {
        String[] untouched = {
            "You have 12 fireworks left, the warning is set at 16: restock or land.",
            "1234567 blocks in a straight line.",
            "set elytraFireworkSpeed 1.2",
            "Joined at 20:12:05, 2026-09-23",
            "coverage 95 %, 3 of 64 chunks missing",
            "hp 18.5, 20 armor",
            "stack 1234, 5",
            "total 1234.5",
        };
        for (String line : untouched) assertEquals(line, ChatLogMask.mask(line), line);
    }

    @Test
    void modesChooseWhichLines() {
        String baritone = "[Baritone] > goal -1234567 -7654321";
        String other = "[Meteor] [Xploits] to -1234567, 7654321.";
        assertEquals(baritone, ChatLogMask.apply(Mode.OFF, baritone));
        assertEquals(other, ChatLogMask.apply(Mode.OFF, other));

        assertEquals("[Baritone] > goal *** ***", ChatLogMask.apply(Mode.BARITONE, baritone));
        assertEquals(other, ChatLogMask.apply(Mode.BARITONE, other));

        assertEquals("[Baritone] > goal *** ***", ChatLogMask.apply(Mode.ALL, baritone));
        assertEquals("[Meteor] [Xploits] to ***, ***.", ChatLogMask.apply(Mode.ALL, other));

        assertEquals(baritone, ChatLogMask.apply(Mode.ALL_BUT_BARITONE, baritone));
        assertEquals("[Meteor] [Xploits] to ***, ***.", ChatLogMask.apply(Mode.ALL_BUT_BARITONE, other));
    }

    @Test
    void baritoneIsRecognisedWithLeadingSpacesOnly() {
        assertEquals(" [Baritone] *** ***", ChatLogMask.apply(Mode.BARITONE, " [Baritone] 1234 5678"));
        assertEquals("<p> [Baritone] 1234 5678", ChatLogMask.apply(Mode.BARITONE, "<p> [Baritone] 1234 5678"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", ChatLogMask.apply(Mode.ALL, ""));
        assertEquals(null, ChatLogMask.apply(Mode.ALL, null));
    }

    @Test
    void displayNamesAreFixedSaveKeys() {
        assertEquals("Off", Mode.OFF.toString());
        assertEquals("Baritone", Mode.BARITONE.toString());
        assertEquals("All", Mode.ALL.toString());
        assertEquals("All but Baritone", Mode.ALL_BUT_BARITONE.toString());
    }
}
