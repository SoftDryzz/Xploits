package com.xploits.pvp.crystal.core;

/**
 * Every setting crystal-aura++ exposes (spec §2), in the order and the groups the module shows them: the one
 * list the adapter builds its settings from, and the list {@code CrystalSettingsCoverageTest} must cover, so a
 * new setting cannot ship untested. The names are Meteor's where Meteor has the setting, and they are what a
 * player types and what Meteor saves: never change them.
 */
public enum CrystalSetting {
    TARGET_RANGE("target-range", Group.GENERAL),
    MIN_DAMAGE("min-damage", Group.GENERAL),
    MAX_DAMAGE("max-damage", Group.GENERAL),
    ANTI_SUICIDE("anti-suicide", Group.GENERAL),
    ROTATE("rotate", Group.GENERAL),
    AUTO_SWITCH("auto-switch", Group.GENERAL),
    NO_GAP_SWITCH("no-gap-switch", Group.GENERAL),
    NO_BOW_SWITCH("no-bow-switch", Group.GENERAL),
    ANTI_WEAKNESS("anti-weakness", Group.GENERAL),
    SWING_MODE("swing-mode", Group.GENERAL),
    PLACE("place", Group.PLACE),
    PLACE_RANGE("place-range", Group.PLACE),
    PLACE_WALLS_RANGE("place-walls-range", Group.PLACE),
    FACE_PLACE("face-place", Group.PLACE),
    FACE_PLACE_HEALTH("face-place-health", Group.PLACE),
    FACE_PLACE_DURABILITY("face-place-durability", Group.PLACE),
    BREAK("break", Group.BREAK),
    BREAK_RANGE("break-range", Group.BREAK),
    BREAK_WALLS_RANGE("break-walls-range", Group.BREAK),
    BREAK_ATTEMPTS("break-attempts", Group.BREAK),
    ATTACK_FREQUENCY("attack-frequency", Group.BREAK),
    FAST_BREAK("fast-break", Group.BREAK),
    PAUSE_ON_USE("pause-on-use", Group.PAUSE),
    PAUSE_ON_MINE("pause-on-mine", Group.PAUSE),
    PAUSE_ON_LAG("pause-on-lag", Group.PAUSE),
    PAUSE_MODULES("pause-modules", Group.PAUSE),
    PAUSE_HEALTH("pause-health", Group.PAUSE),
    SELF_BUDGET("self-budget", Group.SAFETY),
    RESERVE("reserve", Group.SAFETY),
    SAFE_SELF_DAMAGE("safe-self-damage", Group.SAFETY);

    /** The module's setting groups, in order; {@link #GENERAL} is Meteor's default group. */
    public enum Group {
        GENERAL("General"),
        PLACE("Place"),
        BREAK("Break"),
        PAUSE("Pause"),
        SAFETY("Safety");

        private final String title;

        Group(String title) {
            this.title = title;
        }

        /** The group's name, as Meteor shows and saves it. */
        public String title() {
            return title;
        }
    }

    private final String id;
    private final Group group;

    CrystalSetting(String id, Group group) {
        this.id = id;
        this.group = group;
    }

    /** The setting's name, as a player types it and Meteor saves it. */
    public String id() {
        return id;
    }

    public Group group() {
        return group;
    }

    /** The setting's description in the catalogs: {@code SETTING_<constant>}. */
    public CrystalText text() {
        return CrystalText.valueOf("SETTING_" + name());
    }
}
