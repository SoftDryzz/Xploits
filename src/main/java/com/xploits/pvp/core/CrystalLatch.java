package com.xploits.pvp.core;

/**
 * The crystal aura auto-pvp drives this activation (crystal-aura++ spec P5, Q4), in pure logic so the
 * order that matters is tested.
 *
 * <p><b>Latched, not read live.</b> The {@code crystal-module} setting is read once, first thing on
 * activation, and afterwards only a real change while auto-pvp runs moves the latch -{@link #change}-.
 * Everything that turns the catalog's {@code crystal-aura} into a real module goes through
 * {@link #resolve}, so the ledger's "is it on?" and the adapter's "turn it off" always ask about the same
 * module.
 *
 * <p><b>The order of a change.</b> The auras taken so far are released while the old value is still
 * latched, and only then does the latch move. The other order asks the new aura -still off- to turn off,
 * leaves the old one on and no longer owned by anybody, and auto-pvp never turns it off again.
 *
 * <p><b>What is not a change.</b> Meteor calls {@code onChanged} whenever it sets a value, including
 * when it loads the saved settings ({@code Settings.fromTag} resets every setting and loads it, each step
 * firing {@code onChanged}); a load with auto-pvp off, or one that writes the value already latched, is
 * no change at all. A flag set around our own writes could not tell those apart; comparing with the
 * latch can.
 */
public final class CrystalLatch {
    private CrystalModule latched = CrystalModule.METEOR;
    /** Whether the aura not selected was on last time it was looked at (Q4's own flag). */
    private boolean otherOn;

    /** The aura driven now. */
    public CrystalModule latched() {
        return latched;
    }

    /** On activation: latches the selection and re-arms the two-auras warning. */
    public void latch(CrystalModule selected) {
        latched = selected;
        otherOn = false;
    }

    /**
     * The setting's {@code onChanged}: acts only while auto-pvp runs and when {@code value} differs from
     * the latch. Then {@code releaseAll} runs with the old value still latched, and the latch moves.
     *
     * @param running    whether auto-pvp is on
     * @param value      the setting's new value
     * @param releaseAll releases every aura and module auto-pvp took, through {@link #resolve}
     * @return whether it acted
     */
    public boolean change(boolean running, CrystalModule value, Runnable releaseAll) {
        if (!running || value == latched) return false;
        releaseAll.run();
        latched = value;
        // The aura not selected is now the other one: whether it is on is a new question.
        otherOn = false;
        return true;
    }

    /** The real module behind a managed module's catalog name, with the latched aura. */
    public String resolve(String managedName) {
        return latched.resolve(managedName);
    }

    /**
     * Q4: whether to warn now that the aura not selected is on. True once each time that starts; its
     * end, every activation and every change of the latch re-arm it. Independent of {@code notify} and
     * of the plan notes.
     */
    public boolean otherAuraStarted(boolean otherAuraOn) {
        boolean started = otherAuraOn && !otherOn;
        otherOn = otherAuraOn;
        return started;
    }
}
