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
    ACTIVITY,
    LIVE_STARTED,
    LIVE_SELF_POP,
    LIVE_OPPONENT_POP,
    LIVE_OPPONENT_DIED,
    LIVE_BIG_HIT,
    LIVE_BY,
    LIVE_OUT_OF,
    CAUSE_NO_TOTEMS,
    CAUSE_DOUBLE_POP,
    CAUSE_TOTEMS_LEFT,
    CAUSE_OUT_OF_TOTEMS,
    CAUSE_SELF_CRYSTAL,
    CAUSE_FALL,
    CAUSE_BURST,
    CAUSE_OUTNUMBERED,
    /** The {@link #CAUSE_OUTNUMBERED} detail when two or more players hit you. */
    CAUSE_OUTNUMBERED_HIT,
    /** The {@link #CAUSE_OUTNUMBERED} detail when only the players close by at once reached two. */
    CAUSE_OUTNUMBERED_CLOSE,
    CAUSE_CRYSTAL_AURA_OFF,
    CAUSE_CRYSTAL_OUTPACED,
    CAUSE_UNDEFENDED,
    CAUSE_OUT_OF_CRYSTALS,
    CAUSE_OUT_OF_OBSIDIAN,
    CAUSE_ANVIL,
    CAUSE_ARMOR_BROKE,
    CAUSE_MELEE,
    CAUSE_GLIDING,
    CAUSE_CHASING,
    CAUSE_LOW_HEALTH_STAYED,
    /** Appended to a cause whose kind also dealt the killing blow. */
    CAUSE_KILLING_BLOW,
    CAUSE_UNCLEAR,
    /** Appended to {@link #CAUSE_UNCLEAR}: the kind that dealt the most known damage. */
    CAUSE_TOP_SOURCE,
    SUMMARY_HEADER,
    SUMMARY_SELF,
    SUMMARY_DAMAGE,
    SUMMARY_DAMAGE_PART,
    SUMMARY_UNSEEN,
    SUMMARY_OFFENSE,
    SUMMARY_MODULES,
    SUMMARY_PHASES,
    SUMMARY_PHASE_PART,
    /** Shown before the kept phases when older ones were trimmed: how many are not shown. */
    SUMMARY_PHASES_EARLIER,
    SUMMARY_CAUSES,
    SUMMARY_CAUSE_LINE,
    SUMMARY_NO_CAUSES,
    DEATH_NOTICE,
    LIST_HEADER,
    LIST_LINE,
    REVIEW_NONE,
    REVIEW_OUT_OF_RANGE,
    REVIEW_CORRUPT,
    /** {@link #REVIEW_CORRUPT} for the console and the log: no file name, no detail, chat only. */
    REVIEW_CORRUPT_LOG,
    /** The fights folder itself could not be listed (not one corrupt file among many). */
    REVIEW_LIST_FAILED,
    /** {@link #REVIEW_LIST_FAILED} for the console and the log: no detail, placeholder-free. */
    REVIEW_LIST_FAILED_LOG,
    SAVE_FAILED,
    /** {@link #SAVE_FAILED} for the console and the log: no detail, it may carry a file path. */
    SAVE_FAILED_LOG,
    /** Cleanup ({@link com.xploits.pvp.recorder.core.FightStore#prune}) failed after a successful save. */
    PRUNE_FAILED,
    /** {@link #PRUNE_FAILED} for the console and the log: no detail, it may carry a file path. */
    PRUNE_FAILED_LOG,
    TICK_FAILED;

    @Override
    public String area() {
        return "recorder";
    }
}
