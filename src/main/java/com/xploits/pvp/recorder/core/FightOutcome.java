package com.xploits.pvp.recorder.core;

/** How a recorded fight ended. */
public enum FightOutcome {
    /** At least one opponent died and the fight went quiet. */
    WON,
    /** You died. */
    LOST,
    /** It went quiet with nobody dead, or it hit the ten-minute cap. */
    ENDED,
    /** Cut short: you left the server or turned the recorder off. */
    ABORTED;

    /** How the outcome is named to the player. */
    public RecorderText label() {
        return RecorderText.valueOf("OUTCOME_" + name());
    }
}
