package com.xploits.pvp.shell.core;

/** Why a block is placed (surround++ spec §4.2): for the panel, the lab and the tests. */
public enum ShellReason {
    /** Into a crystal spot. */
    SPOT,
    /** Crying obsidian where the opponent would have to place a base first. */
    BASE,
    /** In the way of a spot's rays to us. */
    SHIELD,
    /** Something to place the next block against. */
    SUPPORT,
    /** Closing the shell after a totem pop (spec §7.4). */
    CLOSURE,
    /** Filling an opponent's hole (spec §7.2). */
    DENY_HOLE
}
