package com.xploits.pvp.crystal.core;

/**
 * crystal-aura++ refusing while Meteor's crystal-aura is on (spec §2, P5, Q3): it does nothing then, warns
 * once each time a refusal starts, and never turns itself off. One refusal lasts from the first check that
 * finds Meteor's aura on to the first that finds it off.
 */
public final class Refusal {
    /** What a check changed. */
    public enum Change {
        /** Nothing: still refusing, or still not. */
        NONE,
        /** A refusal starts: say the warning, once. */
        STARTED,
        /** A refusal ends: start afresh, since Meteor's aura acted on the crystals in the meantime. */
        ENDED
    }

    private boolean refusing;

    /** A check, with whether Meteor's crystal-aura is on now. */
    public Change update(boolean meteorAuraOn) {
        if (meteorAuraOn == refusing) return Change.NONE;
        refusing = meteorAuraOn;
        return refusing ? Change.STARTED : Change.ENDED;
    }

    /** Whether crystal-aura++ is refusing now. */
    public boolean refusing() {
        return refusing;
    }
}
