package com.xploits.console.core;

/**
 * The frame's characters. The box-drawing ones look right in the window with UTF-8 (spec §3.1); if
 * the console is not in UTF-8 the frame is drawn in ASCII and the window says so.
 */
public enum Glyphs {
    UNICODE('─', "●", "○"),
    ASCII('-', "*", "o");

    private final char horizontal;
    private final String active;
    private final String inactive;

    Glyphs(char horizontal, String active, String inactive) {
        this.horizontal = horizontal;
        this.active = active;
        this.inactive = inactive;
    }

    public char horizontal() {
        return horizontal;
    }

    public String active() {
        return active;
    }

    public String inactive() {
        return inactive;
    }

    public String line(int cols) {
        return String.valueOf(horizontal).repeat(Math.max(0, cols));
    }
}
