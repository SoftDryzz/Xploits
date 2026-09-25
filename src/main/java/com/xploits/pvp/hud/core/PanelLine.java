package com.xploits.pvp.hud.core;

import com.xploits.shared.core.i18n.Msg;

/** One line of the {@code xploits-pvp} HUD panel (spec §3): what it says and what colour it draws in. */
public record PanelLine(Msg text, Tone tone) {
}
