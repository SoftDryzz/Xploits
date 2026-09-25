package com.xploits.bench;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Named numbers from one MEASURE run, in the order they were put. */
public final class Metrics {
    private final Map<String, Double> values = new LinkedHashMap<>();

    /** What a CHECK returns: no numbers. */
    public static Metrics none() {
        return new Metrics();
    }

    public Metrics put(String name, double value) {
        values.put(name, value);
        return this;
    }

    public Map<String, Double> values() {
        return Collections.unmodifiableMap(values);
    }
}
