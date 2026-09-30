package com.xploits.pvp.shell.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A damage oracle with plain numbers: 0 wherever nothing was given. */
final class FakeOracle implements DamageOracle {
    private final Map<Cell, Double> exact = new HashMap<>();
    private final Map<Cell, Double> bound = new HashMap<>();
    /** The spots whose raycast was asked for. */
    final Set<Cell> askedExact = new HashSet<>();

    /** The same damage as the bound and as the exact value: what most tests need. */
    FakeOracle at(int x, int y, int z, double damage) {
        Cell c = new Cell(x, y, z);
        exact.put(c, damage);
        bound.put(c, damage);
        return this;
    }

    FakeOracle bound(int x, int y, int z, double damage) {
        bound.put(new Cell(x, y, z), damage);
        return this;
    }

    FakeOracle exact(int x, int y, int z, double damage) {
        exact.put(new Cell(x, y, z), damage);
        return this;
    }

    @Override
    public double bound(Cell spot) {
        return bound.getOrDefault(spot, 0.0);
    }

    @Override
    public double exact(Cell spot) {
        askedExact.add(spot);
        return exact.getOrDefault(spot, bound(spot));
    }
}
