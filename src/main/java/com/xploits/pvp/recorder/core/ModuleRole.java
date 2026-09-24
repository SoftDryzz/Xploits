package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.ManagedModules;

import java.util.Set;

/** What a combat module was doing for you, so the analysis can ask "was any defense on" by role, not by name. */
public enum ModuleRole {
    CRYSTAL_OFFENSE,
    MELEE_OFFENSE,
    TRAP_OFFENSE,
    DEFENSE,
    TOTEM,
    OTHER;

    private static final Set<String> CRYSTAL = Set.of("crystal-aura", "anchor-aura", "bed-aura");
    private static final Set<String> MELEE = Set.of("kill-aura");
    private static final Set<String> TRAP = Set.of("auto-trap", "auto-web", "auto-anvil", "auto-city");
    // Those auto-pvp never manages (they lock you in), but that still defend you when you turn them on.
    private static final Set<String> SELF_DEFENSE = Set.of("self-trap", "self-web", "self-anvil", "burrow");
    private static final Set<String> TOTEMS = Set.of("auto-totem", "offhand");

    /** The role of a Meteor or Xploits module by its name; anything unknown is {@link #OTHER}. */
    public static ModuleRole of(String module) {
        if (module == null) return OTHER;
        if (CRYSTAL.contains(module)) return CRYSTAL_OFFENSE;
        if (MELEE.contains(module)) return MELEE_OFFENSE;
        if (TRAP.contains(module)) return TRAP_OFFENSE;
        if (ManagedModules.isDefensive(module) || SELF_DEFENSE.contains(module)) return DEFENSE;
        if (TOTEMS.contains(module)) return TOTEM;
        return OTHER;
    }
}
