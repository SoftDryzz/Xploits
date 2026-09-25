package com.xploits.pvp.profile.core;

/**
 * Decides when unticking {@code use-crystal-aura} warns that the director loses the autobreak (design,
 * precise rules "Autobreak warning"): only on a true to false change made by hand, never while a profile
 * is being applied, and again on every such change. Fed every value the setting takes, so it always
 * knows the previous one.
 */
public final class AutobreakWatch {
    private boolean previous = true;

    /**
     * @param value  the setting's new value
     * @param byHand {@code false} while a profile is being applied (or the value is being loaded)
     * @return whether to warn now
     */
    public boolean changed(boolean value, boolean byHand) {
        boolean warn = byHand && previous && !value;
        previous = value;
        return warn;
    }
}
