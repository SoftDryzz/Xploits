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
    FOREIGN_CRYSTAL
}
