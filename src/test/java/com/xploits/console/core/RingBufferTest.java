package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnilloTest {
    @Test
    void daLaVueltaAlLlenarse() {
        Anillo<Integer> a = new Anillo<>(3);
        for (int i = 1; i <= 5; i++) a.agregar(i);
        assertEquals(3, a.tamano());
        assertEquals(List.of(3, 4, 5), a.ultimos(10, x -> true));
    }

    @Test
    void ultimosFiltraYRespetaElOrden() {
        Anillo<Integer> a = new Anillo<>(5);
        for (int i = 1; i <= 5; i++) a.agregar(i);
        assertEquals(List.of(2, 4), a.ultimos(10, x -> x % 2 == 0));
        assertEquals(List.of(4, 5), a.ultimos(2, x -> true));
        assertEquals(List.of(), a.ultimos(0, x -> true));
    }

    @Test
    void sinCapacidadNoHayAnillo() {
        assertThrows(IllegalArgumentException.class, () -> new Anillo<Integer>(0));
    }
}
