package com.xploits.bench.core;

/**
 * Keeps the crystal stack full during a measured crystal-aura run (crystal-aura++ R3-7), so ammunition never
 * decides a run: after every tick the bench tops the stack back up to {@value #FULL}, and this says by how much,
 * and keeps the run's tally of what the refills added (the crystals the run used). One instance serves one run.
 * Pure and deterministic.
 */
public final class CrystalRefill {
    /** A full stack of end crystals, as the standard loadout gives it. */
    public static final int FULL = 64;

    private int used;

    /**
     * The crystals to add to a stack of end crystals holding {@code count} so that it holds {@value #FULL}, added
     * to the run's tally.
     *
     * @throws IllegalArgumentException when {@code count} is not from 1 to {@value #FULL} (an empty slot is not a
     *                                  stack of crystals)
     */
    public int topUp(int count) {
        if (count < 1 || count > FULL) {
            throw new IllegalArgumentException("a stack of " + count + " end crystal(s)");
        }
        int amount = FULL - count;
        used += amount;
        return amount;
    }

    /** The crystals the refills of this run added so far. */
    public int used() {
        return used;
    }
}
