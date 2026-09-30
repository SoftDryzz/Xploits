package com.xploits.pvp.shell.core;

/** The two grounds most tests stand on, each with one opponent standing four blocks east. */
final class Scenes {
    private Scenes() {
    }

    /** A floor no crystal can stand on (deepslate, say): every spot at head height needs its base placed first. */
    static ShellSnapshot.Builder open() {
        return ShellSnapshot.builder().floor(BlockKind.OTHER).hostile(new Vec(4.5, 0, 0.5));
    }

    /** An obsidian floor: every air cell at feet level is a spot with its base already there. */
    static ShellSnapshot.Builder obsidianFloor() {
        return ShellSnapshot.builder().floor(BlockKind.OBSIDIAN).hostile(new Vec(4.5, 0, 0.5));
    }
}
