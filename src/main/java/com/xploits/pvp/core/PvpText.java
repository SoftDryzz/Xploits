package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.MessageKey;

/** Texts of the auto-pvp module. Pure: the director, the action watch and the ally policy use it too. */
public enum PvpText implements MessageKey {
    MODULE_DESC,
    SETTING_TARGET_RANGE,
    SETTING_APPROACH_DISTANCE,
    SETTING_THREAT_MARGIN,
    SETTING_SYNC_FRIENDS,
    SETTING_NOTIFY,
    SETTING_NOTIFY_SOUND,
    NOTHING,
    NONE,
    JOIN_COMMA,
    JOIN_AND,
    JOIN_SEMICOLON,
    JOIN_PLUS,
    CONCAT,
    ALLY_FRIEND,
    ALLY_COURIER,
    ALLY_TPY_USER,
    TOTEM_FLOOR,
    AURA_NO_CRYSTALS,
    SHORTAGE,
    SHORTAGE_SHARED,
    MATERIAL_CRYSTALS,
    MATERIAL_OBSIDIAN,
    MATERIAL_WEBS,
    MATERIAL_ANVILS,
    MATERIAL_OTHER,
    IDLE_ALONE,
    IDLE_TOGETHER,
    SUSPECTS_OF,
    INNOCENT_SURROUND,
    INNOCENT_HOLE_FILLER,
    INNOCENT_AUTO_WEB,
    INNOCENT_DEFAULT,
    SUSPECTS_CRYSTAL_AURA,
    SUSPECTS_AUTO_TRAP,
    SUSPECTS_AUTO_WEB,
    SUSPECTS_AUTO_ANVIL,
    SUSPECTS_SURROUND,
    SUSPECTS_HOLE_FILLER,
    SUSPECTS_DEFAULT,
    NOT_ATTACKING,
    FRIENDS_ADDED,
    FRIENDS_REMOVED,
    PHASE,
    TARGET_SUFFIX,
    THREATENED,
    CALM,
    NOT_ENABLING,
    RELEASED,
    CRYSTAL_AURA_ALREADY_ON,
    ALREADY_ON,
    OUT_OF_RESOURCES_NONE,
    OUT_OF_RESOURCES,
    OUT_OF_RESOURCES_ITEM,
    READING,
    STATUS_OFF,
    STATUS_UNREAD,
    STATUS,
    STATUS_TARGET,
    STATUS_TARGET_DISTANCE,
    STATUS_SELF,
    STATUS_IN_HOLE,
    STATUS_GLIDING,
    STATUS_CRYSTALS,
    HOSTILE,
    HOSTILES,
    STATUS_ALLY,
    STATUS_SKIPPED,
    STATUS_WARNING,
    FRIENDS_SYNC_OFF,
    FRIENDS_NONE_TRUSTING,
    FRIENDS_SYNCED,
    IDLE_NONE,
    IDLE_LINE,
    IDLE_PART,
    IDLE_JOINT,
    STATE_SIN_COMBATE,
    STATE_ACERCAMIENTO,
    STATE_SUPERFICIE,
    STATE_RODEADO,
    STATE_ENTERRADO,
    STATE_PERSECUCION,
    STATE_SIN_RECURSOS,
    POSTURE_TRANQUILO,
    POSTURE_AMENAZADO;

    /** How a phase of the fight is named to the player. */
    public static PvpText of(CombatState state) {
        return valueOf("STATE_" + state.name());
    }

    /** How a defensive posture is named to the player. */
    public static PvpText of(CombatPosture posture) {
        return valueOf("POSTURE_" + posture.name());
    }

    @Override
    public String area() {
        return "pvp";
    }
}
