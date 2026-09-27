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

    /**
     * The self damage the budget reads: the exact one ({@link ExplosionMath}) checked like any damage, and never
     * below Meteor's {@code selfDamage}. A lower value is replaced by Meteor's, so the budget never counts less
     * than Meteor predicts.
     */
    static double budgetSelf(double budgetSelfDamage, double selfDamage) {
        return Math.max(selfDamage, check(budgetSelfDamage, "budget self damage"));
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
     * The damage summed over these targets, exactly as Meteor sums it (lines 1190-1210:
     * {@code float damage = 0; ... damage += dmg}): in {@code float}, in the order of the targets given.
     * A {@code double} sum can land on the other side of min-damage, so ++ would place where Meteor
     * would not. A target with no entry adds nothing.
     */
    static float sum(Map<String, Double> damage, Collection<String> targets) {
        float total = 0;
        for (String name : targets) total += damage.getOrDefault(name, 0.0).floatValue();
        return total;
    }
}
