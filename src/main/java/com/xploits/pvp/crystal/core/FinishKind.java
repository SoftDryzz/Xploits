package com.xploits.pvp.crystal.core;

/**
 * What a finishing-grade crystal does to its target (task B0c, spec Amendment 2026-09-29): {@link #KILL} when the
 * target holds no totem in either hand and his hands are visible (at least one shows an item), so the crystal
 * kills him; {@link #POP} when he holds a totem, or when both hands look empty (a server that hides equipment
 * shows that, and it must never read as "no totem"); {@link #NONE} when the crystal is not finishing-grade.
 */
public enum FinishKind {
    NONE, POP, KILL
}
