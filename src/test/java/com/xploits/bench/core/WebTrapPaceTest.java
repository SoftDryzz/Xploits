package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task A2+ requirement 5: the web and trap cooldowns are independent, each firing on its own period. */
class WebTrapPaceTest {
    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(100, WebTrapPace.WEB_EVERY);
        assertEquals(200, WebTrapPace.TRAP_EVERY);
    }

    @Test
    void webFiresEveryWebEveryTicks() {
        assertFalse(WebTrapPace.webDueAt(99));
        assertTrue(WebTrapPace.webDueAt(100));
        assertFalse(WebTrapPace.webDueAt(101));
        assertTrue(WebTrapPace.webDueAt(200));
    }

    @Test
    void trapFiresEveryTrapEveryTicksIndependentlyOfWeb() {
        assertFalse(WebTrapPace.trapDueAt(100));
        assertTrue(WebTrapPace.trapDueAt(200));
        assertFalse(WebTrapPace.trapDueAt(300));
        assertTrue(WebTrapPace.trapDueAt(400));
    }
}
