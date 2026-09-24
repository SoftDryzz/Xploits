package com.xploits.pvp.recorder.core;

/** Who was steering the fight: you alone, auto-pvp, or both in turns. */
public enum FightMode {
    /** auto-pvp was never on. */
    MANUAL,
    /** auto-pvp was on for at least {@link #AUTO_PVP_PERCENT} % of the seconds. */
    AUTO_PVP,
    /** auto-pvp was on for part of the fight. */
    MIXED;

    public static final int AUTO_PVP_PERCENT = 90;

    /** The mode of a fight from how many of its seconds had auto-pvp on (integer maths: 27 of 30 is exactly 90 %). */
    public static FightMode of(int autoPvpSeconds, int seconds) {
        if (autoPvpSeconds <= 0) return MANUAL;
        return autoPvpSeconds * 100L >= (long) AUTO_PVP_PERCENT * seconds ? AUTO_PVP : MIXED;
    }

    /** How the mode is named to the player. */
    public RecorderText label() {
        return RecorderText.valueOf("MODE_" + name());
    }
}
