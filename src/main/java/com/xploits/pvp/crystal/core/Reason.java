package com.xploits.pvp.crystal.core;

/** Why a decision or a budget verdict came out as it did. */
public enum Reason {
    /** Nothing to do this tick. */
    NOTHING_TO_DO,
    /** Placing leaves at least the reserve; breaking our own crystal leaves at least the floor. */
    WITHIN_BUDGET,
    /** Placing is allowed only by the safe mode: tiny self damage and the floor still kept. */
    SAFE_SELF_DAMAGE,
    /** Placing refused: it would leave less than the reserve and its self damage is not tiny. */
    OVER_RESERVE,
    /** Refused: it would leave less than the floor (a tiny placement, or breaking our own crystal). */
    BELOW_FLOOR,
    /** Breaking a crystal we did not place: Meteor's rules only, the budget is never asked (spec P2). */
    FOREIGN_CRYSTAL,
    /** The {@code self-budget} setting is off: Meteor's rules only. */
    BUDGET_OFF,
    /** No player is a target this tick (Meteor does nothing then, lines 716-719). */
    NO_TARGETS,
    /** Weakness would stop the attack: swap to an item that hurts the crystal first (lines 826-838). */
    ANTI_WEAKNESS,
    /** Meteor's crystal-aura is on, so crystal-aura++ does nothing (spec §2, Q3); the adapter says this. */
    METEOR_AURA_ON,
    /**
     * Task B0a: a finishing-grade crystal (kills the target or pops his totem) placed or broken through the
     * override, past what {@code max-damage}, {@code anti-suicide}, the reserve, the floor or {@code
     * pause-health} would otherwise allow, only while a totem backs it.
     */
    FINISHING_BLOW
}
