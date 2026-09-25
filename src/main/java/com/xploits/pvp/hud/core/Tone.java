package com.xploits.pvp.hud.core;

/**
 * The colour of one {@link PanelLine} (spec §3, "Precise rules" -&gt; Tone colours). Fixed and
 * language-independent: the same five colours whatever the player's chosen language is.
 */
public enum Tone {
    NORMAL(0xFFFFFF),
    MUTED(0x8A8A8A),
    GOOD(0x55FF55),
    WARN(0xFFAA00),
    DANGER(0xFF5555);

    private final int rgb;

    Tone(int rgb) {
        this.rgb = rgb;
    }

    /** The colour as {@code 0xRRGGBB}, for the renderer to turn into whatever colour type it needs. */
    public int rgb() {
        return rgb;
    }
}
