package com.xploits.pvp.crystal.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Checks shared by the records that carry damage values. */
final class Damage {
    private Damage() {}

    /** A damage value is finite and never negative. */
    static double check(double value, String what) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(what + " " + value);
        return value;
    }

    /** An unmodifiable copy of the damage per target name, keeping the order it was given in. */
    static Map<String, Double> copyOf(Map<String, Double> damage, String what) {
        Objects.requireNonNull(damage, what);
        Map<String, Double> copy = new LinkedHashMap<>();
        damage.forEach((name, value) -> {
            Objects.requireNonNull(name, what + " target");
            Objects.requireNonNull(value, what + " of " + name);
            copy.put(name, check(value, what + " of " + name));
        });
        return Collections.unmodifiableMap(copy);
    }

    /**
     * The damage summed over these targets (Meteor, lines 1198-1210: summed over all targets). A target
     * with no entry adds nothing.
     */
    static double sum(Map<String, Double> damage, Collection<String> targets) {
        double total = 0;
        for (String name : targets) total += damage.getOrDefault(name, 0.0);
        return total;
    }
}
