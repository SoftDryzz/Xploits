package com.xploits.pvp.core;

/** The phases of a fight (spec §4.1). */
public enum CombatState {
    /** No target in reach: everything taken is released. */
    NO_COMBAT,
    /** Target in sight but far away. */
    APPROACH,
    /** Target close, on foot, without a surround and not burrowed. */
    SURFACE,
    /** The target has a surround up. */
    SURROUNDED,
    /** The target is inside a block. */
    BURROWED,
    /**
     * The <b>target</b> is gliding with an elytra and is out of crystal range (redesign §4.1). Whether
     * you fly no longer classifies anything: on this server people fly almost all the time.
     */
    CHASE,
    /** None of the modules the phase called for can be sustained. */
    OUT_OF_RESOURCES
}
