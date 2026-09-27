package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Keeping the crystal stack full during a measured run (R3-7): each top-up adds what the stack lacks to hold
 * {@value CrystalRefill#FULL}, and the run's tally is the sum of them.
 */
class CrystalRefillTest {
    @Test
    void aFullStackGetsNothing() {
        CrystalRefill refill = new CrystalRefill();
        assertEquals(0, refill.topUp(64));
        assertEquals(0, refill.used());
    }

    @Test
    void aStackShortOfFullGetsExactlyWhatItLacks() {
        CrystalRefill refill = new CrystalRefill();
        assertEquals(1, refill.topUp(63));
        assertEquals(3, refill.topUp(61));
        assertEquals(63, refill.topUp(1));
    }

    @Test
    void theRunsTallyIsTheSumOfItsTopUps() {
        CrystalRefill refill = new CrystalRefill();
        refill.topUp(62);
        refill.topUp(64);
        refill.topUp(63);
        refill.topUp(60);
        assertEquals(7, refill.used());
    }

    @Test
    void aStackThatCannotBeAStackOfCrystalsIsRefused() {
        CrystalRefill refill = new CrystalRefill();
        assertThrows(IllegalArgumentException.class, () -> refill.topUp(0), "an empty slot holds no crystals");
        assertThrows(IllegalArgumentException.class, () -> refill.topUp(-1));
        assertThrows(IllegalArgumentException.class, () -> refill.topUp(65), "a stack never holds more than a full one");
        assertEquals(0, refill.used(), "a refused top-up adds nothing");
    }

    @Test
    void aFullStackIsSixtyFour() {
        assertEquals(64, CrystalRefill.FULL);
    }
}
