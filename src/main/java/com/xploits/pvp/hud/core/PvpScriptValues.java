package com.xploits.pvp.hud.core;

import com.xploits.pvp.core.PvpText;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Msg;

/**
 * The five {@code xploits.pvp.*} Starscript values (spec §3 + "Precise rules" -&gt; Starscript), already
 * rendered in {@code catalog}'s language: the same facts the panel shows, never a position.
 *
 * <p>Auto-pvp off, or no world at all (a {@code null} {@link PanelInput}): {@link #state} is the
 * translated word for "off", {@link #posture}, {@link #target} and {@link #distance} are empty, and
 * {@link #profile} is the active profile's name when it is known (auto-pvp off still knows it; with no
 * {@code PanelInput} at all there is nothing to read it from, so it is empty too).
 */
public record PvpScriptValues(String state, String posture, String profile, String target, String distance) {
    /** {@code in} is {@code null} for "no world"; otherwise built the same way the panel is. */
    public static PvpScriptValues of(PanelInput in, Catalog catalog) {
        if (in == null) {
            return new PvpScriptValues(catalog.render(Msg.of(HudText.SCRIPT_OFF)), "", "", "", "");
        }
        if (!in.autoPvpOn()) {
            return new PvpScriptValues(catalog.render(Msg.of(HudText.SCRIPT_OFF)), "", in.profileName(), "", "");
        }
        String target = in.target() == null ? "" : in.target();
        String distance = in.target() == null ? "" : catalog.render(Msg.of(HudText.SCRIPT_DISTANCE, "distance", in.targetDistance()));
        return new PvpScriptValues(
            catalog.render(PvpText.of(in.state())),
            catalog.render(PvpText.of(in.posture())),
            in.profileName(),
            target,
            distance);
    }
}
