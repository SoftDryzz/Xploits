package com.xploits.console.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Los últimos N elementos, en orden de llegada. */
public final class Anillo<T> {
    private final Object[] elementos;
    private int inicio;
    private int tamano;

    public Anillo(int capacidad) {
        if (capacidad <= 0) throw new IllegalArgumentException("un anillo sin capacidad no guarda nada");
        elementos = new Object[capacidad];
    }

    public void agregar(T t) {
        int pos = (inicio + tamano) % elementos.length;
        elementos[pos] = t;
        if (tamano < elementos.length) tamano++;
        else inicio = (inicio + 1) % elementos.length;
    }

    public int tamano() {
        return tamano;
    }

    /** Los {@code n} últimos que cumplen el filtro, del más viejo al más nuevo. */
    @SuppressWarnings("unchecked")
    public List<T> ultimos(int n, Predicate<? super T> filtro) {
        List<T> salida = new ArrayList<>();
        for (int i = tamano - 1; i >= 0 && salida.size() < n; i--) {
            T t = (T) elementos[(inicio + i) % elementos.length];
            if (filtro.test(t)) salida.add(t);
        }
        Collections.reverse(salida);
        return salida;
    }
}
