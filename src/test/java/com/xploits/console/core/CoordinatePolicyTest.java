package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatePolicyTest {
    @Test
    void hidingSendsTheHalfWithoutPosition() {
        assertEquals("log", CoordinatePolicy.pick(true, "chat", "log"));
    }

    @Test
    void showingSendsTheChatHalf() {
        assertEquals("chat", CoordinatePolicy.pick(false, "chat", "log"));
    }

    @Test
    void theSentinelHoldsOnlyWhileHiding() {
        String positioned = "Destination at 1234, -5678";
        assertTrue(CoordinatePolicy.hold(true, positioned));
        assertFalse(CoordinatePolicy.hold(false, positioned));
        assertFalse(CoordinatePolicy.hold(true, "12 fireworks left"));
    }
}
