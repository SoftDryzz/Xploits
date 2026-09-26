package com.xploits.pvp.crystal.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the crystal-aura++ module. Pure: auto-pvp's status reads the late-own-crystals line too. */
public enum CrystalText implements MessageKey {
    MODULE_DESC,
    SETTING_TARGET_RANGE,
    SETTING_MIN_DAMAGE,
    SETTING_MAX_DAMAGE,
    SETTING_ANTI_SUICIDE,
    SETTING_ROTATE,
    SETTING_AUTO_SWITCH,
    SETTING_NO_GAP_SWITCH,
    SETTING_NO_BOW_SWITCH,
    SETTING_ANTI_WEAKNESS,
    SETTING_SWING_MODE,
    SETTING_PLACE,
    SETTING_PLACE_RANGE,
    SETTING_PLACE_WALLS_RANGE,
    SETTING_FACE_PLACE,
    SETTING_FACE_PLACE_HEALTH,
    SETTING_FACE_PLACE_DURABILITY,
    SETTING_BREAK,
    SETTING_BREAK_RANGE,
    SETTING_BREAK_WALLS_RANGE,
    SETTING_BREAK_ATTEMPTS,
    SETTING_ATTACK_FREQUENCY,
    SETTING_FAST_BREAK,
    SETTING_PAUSE_ON_USE,
    SETTING_PAUSE_ON_MINE,
    SETTING_PAUSE_ON_LAG,
    SETTING_PAUSE_MODULES,
    SETTING_PAUSE_HEALTH,
    SETTING_RISK,
    SETTING_SELF_BUDGET,
    SETTING_RESERVE,
    SETTING_SAFE_SELF_DAMAGE,
    /** What each value of {@code risk} means ({@link RiskLevel#text()}), shown after the setting's own description. */
    RISK_SAFE,
    RISK_BALANCED,
    RISK_AGGRESSIVE,
    RISK_CUSTOM,
    /** Said once each time crystal-aura++ starts refusing because Meteor's crystal-aura is on (spec §2, P5). */
    METEOR_AURA_ON,
    /** The status line with the late own crystals (Q2, Q6); {@code {count}}. */
    STATUS_LATE_OWN;

    @Override
    public String area() {
        return "crystal";
    }
}
