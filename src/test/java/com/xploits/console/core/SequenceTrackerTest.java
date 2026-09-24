package com.xploits.console.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecuenciaTest {
    @Test
    void seguidosNoPierdenNada() {
        Secuencia s = new Secuencia();
        assertEquals(0, s.hueco("a", 1));
        assertEquals(0, s.hueco("a", 2));
    }

    @Test
    void unSaltoDeCincoSonCuatroPerdidos() {
        Secuencia s = new Secuencia();
        s.hueco("a", 1);
        assertEquals(4, s.hueco("a", 6));
    }

    @Test
    void retrocederEsUnFallo() {
        Secuencia s = new Secuencia();
        s.hueco("a", 5);
        assertThrows(IllegalStateException.class, () -> s.hueco("a", 5));
        assertThrows(IllegalStateException.class, () -> s.hueco("a", 3));
    }

    @Test
    void unaSesionNuevaEmpiezaDeCero() {
        Secuencia s = new Secuencia();
        s.hueco("a", 10);
        assertEquals(0, s.hueco("b", 0));
    }
}
