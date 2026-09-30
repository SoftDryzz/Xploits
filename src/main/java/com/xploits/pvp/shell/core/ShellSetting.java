package com.xploits.pvp.shell.core;

/** surround++'s settings: the name a player types and Meteor saves, fixed here; the description in the catalogs. */
public enum ShellSetting {
    BLOCKS_PER_TICK("blocks-per-tick"),
    USE_CRYING_OBSIDIAN("use-crying-obsidian"),
    MOVE_TO_HOLE("move-to-hole"),
    DENY_HOLES("deny-holes"),
    BREAK_CRYSTALS("break-crystals"),
    BURROW("burrow"),
    REACH("reach");

    private final String id;

    ShellSetting(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The description: {@code SETTING_<constant>}. */
    public ShellText text() {
        return ShellText.valueOf("SETTING_" + name());
    }
}
