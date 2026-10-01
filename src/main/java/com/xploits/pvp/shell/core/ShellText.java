package com.xploits.pvp.shell.core;

import com.xploits.shared.core.i18n.MessageKey;

/** surround++'s texts (catalog area {@code shell}). */
public enum ShellText implements MessageKey {
    MODULE_DESC,
    SETTING_BLOCKS_PER_TICK,
    SETTING_USE_CRYING_OBSIDIAN,
    SETTING_MOVE_TO_HOLE,
    SETTING_DENY_HOLES,
    SETTING_BREAK_CRYSTALS,
    SETTING_BURROW,
    SETTING_REACH,
    /** Said once while the hotbar holds neither obsidian nor crying obsidian; again once it did and runs out. */
    NO_BLOCKS,
    /** Said once each time Meteor's surround or self-trap is found on beside surround++; {@code {module}} names it. */
    METEOR_SHELL_ON;

    @Override
    public String area() {
        return "shell";
    }
}
