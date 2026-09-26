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
    SETTING_USE_MODULE,
    SETTING_NEXT_PROFILE,
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
    PROFILE_OFF,
    MODULE_MISSING,
    MODULE_MISSING_SKIP,
    TOTEM_FLOOR,
    AURA_NO_CRYSTALS,
    SHORTAGE,
    SHORTAGE_SHARED,
    MATERIAL_CRYSTALS,
    MATERIAL_OBSIDIAN,
    MATERIAL_WEBS,
    MATERIAL_ANVILS,
    MATERIAL_STRING,
    MATERIAL_SLABS,
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
    STATUS_PROFILE,
    STATUS_PROFILE_OFF,
    STATUS_MISSING,
    FRIENDS_SYNC_OFF,
    FRIENDS_NONE_TRUSTING,
    FRIENDS_SYNCED,
    IDLE_NONE,
    IDLE_LINE,
    IDLE_PART,
    IDLE_JOINT,
    STATE_NO_COMBAT,
    STATE_APPROACH,
    STATE_SURFACE,
    STATE_SURROUNDED,
    STATE_BURROWED,
    STATE_CHASE,
    STATE_OUT_OF_RESOURCES,
    POSTURE_CALM,
    POSTURE_THREATENED;

    /** How a phase of the fight is named to the player. */
    public static PvpText of(CombatState state) {
        return valueOf("STATE_" + state.name());
    }

    /** How a defensive posture is named to the player. */
    public static PvpText of(CombatPosture posture) {
        return valueOf("POSTURE_" + posture.name());
    }

    /** How the material of a stack is named to the player; the pickaxe is not material that runs out. */
    public static PvpText of(Resource resource) {
        return switch (resource) {
            case CRYSTALS -> MATERIAL_CRYSTALS;
            case OBSIDIAN -> MATERIAL_OBSIDIAN;
            case WEBS -> MATERIAL_WEBS;
            case ANVILS -> MATERIAL_ANVILS;
            case STRING -> MATERIAL_STRING;
            case SLABS -> MATERIAL_SLABS;
            case PICKAXE -> MATERIAL_OTHER;
        };
    }

    @Override
    public String area() {
        return "pvp";
    }
}
