package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerdidasTest {
    @Test
    void soloLaPrimeraAvisaYSeDrenanUnaVez() {
        Perdidas p = new Perdidas();
        assertTrue(p.descartado());
        assertFalse(p.descartado());
        assertFalse(p.descartado());
        assertEquals(OptionalLong.of(3), p.drenar());
        assertEquals(OptionalLong.empty(), p.drenar());
        assertFalse(p.descartado());
        assertEquals(OptionalLong.of(1), p.drenar());
    }
}
