package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RingBufferTest {
    @Test
    void wrapsAroundWhenFull() {
        RingBuffer<Integer> r = new RingBuffer<>(3);
        for (int i = 1; i <= 5; i++) r.add(i);
        assertEquals(3, r.size());
        assertEquals(List.of(3, 4, 5), r.latest(10, x -> true));
    }

    @Test
    void latestFiltersAndKeepsTheOrder() {
        RingBuffer<Integer> r = new RingBuffer<>(5);
        for (int i = 1; i <= 5; i++) r.add(i);
        assertEquals(List.of(2, 4), r.latest(10, x -> x % 2 == 0));
        assertEquals(List.of(4, 5), r.latest(2, x -> true));
        assertEquals(List.of(), r.latest(0, x -> true));
    }

    @Test
    void aRingWithoutCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<Integer>(0));
    }
}
