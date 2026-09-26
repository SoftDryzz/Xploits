package com.xploits.pvp.crystal.core;

/**
 * How much health crystal-aura++'s placements keep back (setting {@code risk}): the reserve R of the
 * self-damage budget ({@link SelfBudget}). Only R moves: the floor F, which breaking our own crystal must
 * leave, and every check of Meteor's stay as they are, so each level still only ever does what Meteor's
 * crystal-aura would also do. The display names are what Meteor saves and a player types: never change them.
 */
public enum RiskLevel {
    /** R = 5, the same as Meteor's pause-health. Experimental. */
    SAFE("Safe", 5.0),
    /** R = 3.5: the default, and recommended. */
    BALANCED("Balanced", 3.5),
    /** R = F: a placement may leave exactly the floor, and its own crystal can still be broken. Experimental. */
    AGGRESSIVE("Aggressive", SelfBudget.FLOOR),
    /** R = the {@code reserve} setting. */
    CUSTOM("Custom", Double.NaN);

    private final String display;
    private final double reserve;

    RiskLevel(String display, double reserve) {
        this.display = display;
        this.reserve = reserve;
    }

    /**
     * The reserve R this level keeps.
     *
     * @param customReserve the {@code reserve} setting, which only {@link #CUSTOM} reads
     */
    public double reserve(double customReserve) {
        return this == CUSTOM ? customReserve : reserve;
    }

    /** This level's description in the catalogs: {@code RISK_<constant>}. */
    public CrystalText text() {
        return CrystalText.valueOf("RISK_" + name());
    }

    @Override
    public String toString() {
        return display;
    }
}
