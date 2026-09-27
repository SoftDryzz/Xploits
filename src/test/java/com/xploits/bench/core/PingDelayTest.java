package com.xploits.bench.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R3-12: the round trip a crystal-aura MEASURE plays over, and the delay each direction of the bench's
 * simulated connection adds.
 */
class PingDelayTest {
    @Test
    void theDefaultRoundTripIsOneHundredMilliseconds() {
        assertEquals(100, PingDelay.effective(null));
        assertEquals(100, PingDelay.BENCH_PING_MS);
    }

    @Test
    void anOverrideReplacesTheDefault() {
        assertEquals(0, PingDelay.effective(0));
        assertEquals(250, PingDelay.effective(250));
    }

    @Test
    void aNegativeOverrideIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> PingDelay.effective(-1));
    }

    @Test
    void eachDirectionGetsHalfTheRoundTrip() {
        assertEquals(50, PingDelay.eachWayMs(100));
        assertEquals(0, PingDelay.eachWayMs(0));
    }

    @Test
    void anOddRoundTripRoundsTheEachWayDelayDown() {
        // The two directions then add up to one millisecond less than the round trip, never more.
        assertEquals(50, PingDelay.eachWayMs(101));
    }

    @Test
    void aNegativeRoundTripIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> PingDelay.eachWayMs(-5));
    }
}
