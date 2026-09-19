package com.xploits.pvp.core;

import java.util.List;

/**
 * El catálogo de los seis módulos que el director dirige (spec §6). Los nombres están verificados
 * contra las fuentes de meteor-client 1.21.11. Los módulos "de siempre" (auto-totem, auto-armor,
 * offhand, auto-weapon) NO están aquí a propósito: son del jugador y el director no los toca.
 */
public final class ManagedModules {
    public static final ManagedModule CRYSTAL_AURA = new ManagedModule("crystal-aura", Resource.CRYSTALS, 1);
    public static final ManagedModule AUTO_TRAP = new ManagedModule("auto-trap", Resource.OBSIDIAN, 8);
    public static final ManagedModule AUTO_WEB = new ManagedModule("auto-web", Resource.WEBS, 1);
    public static final ManagedModule SURROUND = new ManagedModule("surround", Resource.OBSIDIAN, 4);
    public static final ManagedModule AUTO_ANVIL = new ManagedModule("auto-anvil", Resource.ANVILS, 1);
    public static final ManagedModule AUTO_CITY = new ManagedModule("auto-city", Resource.PICKAXE, 1);

    public static final List<ManagedModule> ALL =
        List.of(CRYSTAL_AURA, AUTO_TRAP, AUTO_WEB, SURROUND, AUTO_ANVIL, AUTO_CITY);

    private ManagedModules() {}
}
