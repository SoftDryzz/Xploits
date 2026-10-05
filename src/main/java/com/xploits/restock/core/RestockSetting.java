package com.xploits.restock.core;

/** restock's settings (spec §5): the id a player types and Meteor saves, fixed here; the description in the catalogs. */
public enum RestockSetting {
    MARK_KEY("mark-key"),
    MAX_DISTANCE("max-distance"),
    USE_STASH_KEEPER("use-stash-keeper"),
    USE_CARRIED_SHULKERS("use-carried-shulkers"),
    BARITONE_SETTINGS("baritone-settings"),
    BARITONE_PREFIX("baritone-prefix"),
    STOP_NEAR_PLAYERS("stop-near-players"),
    PLAYER_DISTANCE("player-distance"),
    MIN_HEALTH("min-health");

    private final String id;

    RestockSetting(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The description: {@code SETTING_<constant>}. */
    public RestockText text() {
        return RestockText.valueOf("SETTING_" + name());
    }
}
