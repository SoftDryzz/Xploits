package com.xploits.pvp.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The shell auto-pvp drives this activation (surround++ spec §8), latched as {@link CrystalLatch} latches the crystal
 * aura and for the same reasons: read once on activation; {@code onChanged} only notes a value, because a Meteor load sets
 * it more than once in one go; the first thing each tick compares what is left with the latch, and a real change releases
 * everything taken with the old shell still latched before the latch moves.
 */
public final class ShellLatch {
    private final BooleanSupplier running;
    private final Runnable releaseAll;
    private final Consumer<ShellModule> moved;

    private ShellModule latched = ShellModule.METEOR;
    private ShellModule pending;

    /**
     * @param running    whether auto-pvp is on
     * @param releaseAll releases every module auto-pvp took; it runs with the old shell latched
     * @param moved      told after the latch has moved, with the new shell
     */
    public ShellLatch(BooleanSupplier running, Runnable releaseAll, Consumer<ShellModule> moved) {
        this.running = Objects.requireNonNull(running);
        this.releaseAll = Objects.requireNonNull(releaseAll);
        this.moved = Objects.requireNonNull(moved);
    }

    public ShellModule latched() {
        return latched;
    }

    /** On activation: latches the selection and drops anything pending. */
    public void latch(ShellModule selected) {
        latched = selected;
        pending = null;
    }

    /** The setting's {@code onChanged}: only notes the value; {@link #settle} decides. */
    public void requested(ShellModule value) {
        pending = value;
    }

    /** First thing each tick; whether the latch moved. */
    public boolean settle() {
        if (pending == null) return false;
        ShellModule value = pending;
        pending = null;
        if (!running.getAsBoolean() || value == latched) return false;
        releaseAll.run();
        latched = value;
        moved.accept(value);
        return true;
    }

    /** The real module behind a managed module's catalog name, with the latched shell. */
    public String resolve(String managedName) {
        return latched.resolve(managedName);
    }
}
