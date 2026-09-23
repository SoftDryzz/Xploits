package com.xploits.shared.core.i18n;

/**
 * Startup rules of spec §5. Meteor builds modules before it loads saved settings, and loading a
 * setting fires its {@code onChanged}: so while loading, setting changes are ignored and the file
 * decides. The adapter forces the setting to {@link #fileChoice()} on the first tick and only then
 * calls {@link #finishLoading()} — that forced {@code set} fires {@code onChanged} too.
 */
public final class LanguageSync {
    public enum Change { IGNORED, UNCHANGED, CHANGED }

    private LanguageChoice current;
    private boolean loading = true;

    public LanguageSync(LanguageChoice fromFile) {
        this.current = fromFile;
    }

    public LanguageChoice current() {
        return current;
    }

    public boolean loading() {
        return loading;
    }

    /** What the file says, which is what the setting must show after loading. */
    public LanguageChoice fileChoice() {
        return current;
    }

    public void finishLoading() {
        loading = false;
    }

    /** The Meteor setting changed: Meteor loading it, or the player in the ClickGUI. */
    public Change settingChanged(LanguageChoice value) {
        if (loading) return Change.IGNORED;
        return choose(value);
    }

    /** The player chose, by command or ClickGUI. Always honoured. */
    public Change choose(LanguageChoice value) {
        if (value == current) return Change.UNCHANGED;
        current = value;
        return Change.CHANGED;
    }
}
