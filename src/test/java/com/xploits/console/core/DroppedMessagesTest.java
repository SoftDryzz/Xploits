package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedMessagesTest {
    @Test
    void onlyTheFirstWarnsAndTheyDrainOnce() {
        DroppedMessages d = new DroppedMessages();
        assertTrue(d.recordDrop());
        assertFalse(d.recordDrop());
        assertFalse(d.recordDrop());
        assertEquals(OptionalLong.of(3), d.drain());
        assertEquals(OptionalLong.empty(), d.drain());
        assertFalse(d.recordDrop());
        assertEquals(OptionalLong.of(1), d.drain());
    }
}
