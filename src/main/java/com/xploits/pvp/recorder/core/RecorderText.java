package com.xploits.pvp.recorder.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the fight recorder. Pure: the tracker, the analysis and the summary use it too. */
public enum RecorderText implements MessageKey {
    MODULE_DESC,
    SETTING_DEATH_NOTICE,
    SETTING_LIVE_CONSOLE,
    OUTCOME_WON,
    OUTCOME_LOST,
    OUTCOME_ENDED,
    OUTCOME_ABORTED,
    MODE_MANUAL,
    MODE_AUTO_PVP,
    MODE_MIXED,
    KIND_CRYSTAL,
    KIND_EXPLOSION,
    KIND_RESPAWN_POINT,
    KIND_ANVIL,
    KIND_MELEE,
    KIND_PROJECTILE,
    KIND_FALL,
    KIND_FIRE,
    KIND_MAGIC,
    KIND_MOB,
    KIND_OTHER,
    KIND_UNSEEN,
    BY_SELF,
    BY_NONE,
    MATERIAL_CRYSTALS,
    MATERIAL_OBSIDIAN,
    MATERIAL_TOTEMS,
    NOBODY,
    NOTHING,
    JOIN_COMMA,
    CONCAT,
    ON,
    OFF,
    ACTIVITY;

    @Override
    public String area() {
        return "recorder";
    }
}
