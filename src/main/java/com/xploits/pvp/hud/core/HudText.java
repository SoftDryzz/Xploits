package com.xploits.pvp.hud.core;

import com.xploits.shared.core.i18n.MessageKey;

/**
 * Texts of the {@code xploits-pvp} HUD panel and of the {@code xploits.pvp.*} Starscript values.
 * {@link PanelModel} builds every {@link com.xploits.shared.core.i18n.Msg} from these plus, where the
 * meaning already exists there, a handful of {@code PvpText} keys ({@code OUT_OF_RESOURCES_NONE},
 * {@code AURA_NO_CRYSTALS}) so the same fact is not said two different ways in two catalogs.
 */
public enum HudText implements MessageKey {
    /** Auto-pvp off: the panel's only line. {@code {name}} is the active profile, without a modified mark. */
    OFF,
    /** Line 2: {@code {profile}} already carries its own trailing {@code *} when modified. */
    HEADER,
    /** Line 3 with no target. */
    NO_TARGET,
    /** Line 3 with a target. */
    TARGET,
    /** Line 4, the modules the director owns and has on. */
    MODULES_ON,
    /** Line 4, modules released back to the player. */
    MODULES_RELEASED,
    /** Line 4, modules the active profile does not allow. */
    MODULES_OFF_BY_PROFILE,
    RESOURCE_CRYSTALS,
    RESOURCE_TOTEMS,
    RESOURCE_OBSIDIAN,
    /** Line 6, the recorder's live totals. */
    FIGHT,
    /** Danger (a): no totems while in a fight. */
    DANGER_NO_TOTEMS,
    /** Danger (c): a watched resource idle. {@code {material}} is a {@code PvpText.MATERIAL_*} value. */
    DANGER_IDLE,
    JOIN_COMMA,
    JOIN_AND,
    /** {@code {xploits.pvp.state}} with auto-pvp off or no world. */
    SCRIPT_OFF,
    /** One decimal, locale-formatted; used for {@code {xploits.pvp.distance}}. */
    SCRIPT_DISTANCE;

    @Override
    public String area() {
        return "hud";
    }
}
