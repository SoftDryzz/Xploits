package com.xploits.pvp.core;

/** What a managed module needs to carry to be of any use (spec §6). */
public enum Resource {
    CRYSTALS, OBSIDIAN, WEBS, ANVILS, PICKAXE,
    /**
     * Nothing. The three {@code anti-} modules of the defensive posture (redesign §5) neither place nor
     * spend: they only listen and react, so there is nothing to count in the inventory and the resource
     * filter can never drop them. It is declared as a resource, with a minimum of zero, so that the
     * catalog does not have to allow a null {@code needs()}.
     */
    NONE
}
