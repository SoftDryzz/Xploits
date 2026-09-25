package com.xploits.pvp.profile.core;

import com.xploits.shared.core.i18n.MessageKey;

/**
 * Texts of style profiles: {@link ProfileBook} and {@code ProfileStore} return {@link
 * com.xploits.shared.core.i18n.Msg}s built from these; neither ever prints. {@code PROFILE_OFF}, the
 * skip reason a disallowed module gets from the director, is a different concern and stays in {@code
 * PvpText}.
 */
public enum ProfileText implements MessageKey {
    /** {@code use}/{@code delete} of a name not in the book; carries the known names too. */
    PROFILE_UNKNOWN,
    /** {@code save} with a name that does not match {@link PvpProfile#NAME}. */
    PROFILE_BAD_NAME,
    /** {@code save} of a new name while already at {@link ProfileBook#PROFILE_LIMIT} of the player's own. */
    PROFILE_LIMIT_REACHED,
    /** {@code save} of a profile whose allowed set is empty: auto-pvp will only watch. */
    PROFILE_NOTHING_ALLOWED,
    /** {@code save} of a profile that does not allow {@code crystal-aura}: it loses the autobreak. */
    PROFILE_NO_AUTOBREAK,
    PROFILE_SAVED,
    /** A player profile actually removed (a built-in gets {@link #PROFILE_RESET} instead). */
    PROFILE_DELETED,
    /** {@code delete} of a built-in: reset to its factory values instead of removed. */
    PROFILE_RESET,
    /** The profile that just became active, from {@code use} or {@code next}. */
    PROFILE_ACTIVE,
    PROFILE_LIST_HEADER,
    /** One row of {@code profile list}; {@code {active}} is {@link #PROFILE_LIST_ACTIVE} or empty. */
    PROFILE_LIST_LINE,
    /** The suffix {@link #PROFILE_LIST_LINE} appends to the active profile's row. */
    PROFILE_LIST_ACTIVE,
    /** {@code profiles.json} could not be read or understood: chat detail, {@link #PROFILE_FILE_CORRUPT_LOG} for the console/log. */
    PROFILE_FILE_CORRUPT,
    PROFILE_FILE_CORRUPT_LOG,
    /** {@code ProfileStore.save} refused while {@code ProfileStore.locked()}. */
    PROFILE_FILE_LOCKED,
    /** Writing {@code profiles.json} itself failed: chat detail, {@link #PROFILE_SAVE_FAILED_LOG} for the console/log. */
    PROFILE_SAVE_FAILED,
    PROFILE_SAVE_FAILED_LOG,
    /** {@code ProfileStore.resetFile()} moved the corrupt file aside; saving works again. */
    PROFILE_FILE_RESET;

    @Override
    public String area() {
        return "profile";
    }
}
