package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The crystal aura auto-pvp drives this activation (crystal-aura++ spec P5, Q4), in pure logic so the
 * order that matters is tested.
 *
 * <p><b>Latched, not read live.</b> The {@code crystal-module} setting is read once, first thing on
 * activation, and afterwards only a real change while auto-pvp runs moves the latch. Everything that
 * turns the catalog's {@code crystal-aura} into a real module goes through {@link #resolve}, so the
 * ledger's "is it on?" and the adapter's "turn it off" always ask about the same module.
 *
 * <p><b>A change is applied on the next tick, not in {@code onChanged}.</b> Meteor calls
 * {@code onChanged} whenever it sets a value, and a load calls it more than once in one go:
 * {@code Settings.fromTag} first resets every setting -{@code onChanged} with the default- and then
 * loads the saved value -{@code onChanged} again, twice-. Acting on each call would switch to Meteor's
 * aura and straight back for a load that ends where it started. So {@link #requested} only notes the
 * value, and {@link #settle}, the first thing auto-pvp does each tick, compares what is left with the
 * latch: a load that ends on the latched value is no change at all, whatever it went through.
 *
 * <p><b>The order of a change.</b> The auras taken so far are released while the old value is still
 * latched, and only then does the latch move. The other order asks the new aura -still off- to turn off,
 * leaves the old one on and no longer owned by anybody, and auto-pvp never turns it off again.
 */
public final class CrystalLatch {
    private final BooleanSupplier running;
    private final Runnable releaseAll;
    private final Consumer<CrystalModule> moved;

    private CrystalModule latched = CrystalModule.METEOR;
    /** The value last set on the setting and not yet looked at, or {@code null}. */
    private CrystalModule pending;
    /** Whether the aura not selected was on last time it was looked at (Q4's own flag). */
    private boolean otherOn;

    /**
     * @param running    whether auto-pvp is on
     * @param releaseAll releases every aura and module auto-pvp took; it runs with the old aura latched
     * @param moved      told after the latch has moved, with the new aura
     */
    public CrystalLatch(BooleanSupplier running, Runnable releaseAll, Consumer<CrystalModule> moved) {
        this.running = Objects.requireNonNull(running);
        this.releaseAll = Objects.requireNonNull(releaseAll);
        this.moved = Objects.requireNonNull(moved);
    }

    /** The aura driven now. */
    public CrystalModule latched() {
        return latched;
    }

    /** On activation: latches the selection, drops anything pending and re-arms the two-auras warning. */
    public void latch(CrystalModule selected) {
        latched = selected;
        pending = null;
        otherOn = false;
    }

    /** The setting's {@code onChanged}: only notes the value; {@link #settle} decides. */
    public void requested(CrystalModule value) {
        pending = value;
    }

    /**
     * First thing each tick. With something pending, auto-pvp on and a value that differs from the
     * latch: {@code releaseAll} runs with the old value still latched, the latch moves, the two-auras
     * warning is re-armed -the aura not selected is now the other one- and {@code moved} is told.
     *
     * @return whether the latch moved
     */
    public boolean settle() {
        if (pending == null) return false;
        CrystalModule value = pending;
        pending = null;
        if (!running.getAsBoolean() || value == latched) return false;
        releaseAll.run();
        latched = value;
        otherOn = false;
        moved.accept(value);
        return true;
    }

    /** The real module behind a managed module's catalog name, with the latched aura. */
    public String resolve(String managedName) {
        return latched.resolve(managedName);
    }

    /**
     * Q4: whether to warn now that the aura not selected is on. True once each time that starts; its
     * end, every activation and every move of the latch re-arm it. Independent of {@code notify} and
     * of the plan notes.
     */
    public boolean otherAuraStarted(boolean otherAuraOn) {
        boolean started = otherAuraOn && !otherOn;
        otherOn = otherAuraOn;
        return started;
    }

    /**
     * The "already on" warning for the latched aura (spec I1): said on activation, and when the latch
     * moves to an aura that is already on. That one is the player's, and auto-pvp will not turn it off.
     */
    public Optional<Msg> alreadyOn(boolean latchedAuraOn) {
        if (!latchedAuraOn) return Optional.empty();
        return Optional.of(Msg.of(PvpText.CRYSTAL_AURA_ALREADY_ON,
            "module", latched.moduleName()));
    }
}
