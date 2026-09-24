package com.xploits.console.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** The last N elements, in order of arrival. */
public final class RingBuffer<T> {
    private final Object[] elements;
    private int start;
    private int size;

    public RingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("a ring without capacity holds nothing");
        elements = new Object[capacity];
    }

    public void add(T t) {
        int pos = (start + size) % elements.length;
        elements[pos] = t;
        if (size < elements.length) size++;
        else start = (start + 1) % elements.length;
    }

    public int size() {
        return size;
    }

    /** The {@code n} latest that pass the filter, oldest first. */
    @SuppressWarnings("unchecked")
    public List<T> latest(int n, Predicate<? super T> filter) {
        List<T> result = new ArrayList<>();
        for (int i = size - 1; i >= 0 && result.size() < n; i--) {
            T t = (T) elements[(start + i) % elements.length];
            if (filter.test(t)) result.add(t);
        }
        Collections.reverse(result);
        return result;
    }
}
