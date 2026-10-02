package com.xploits.restock.core;

import com.xploits.printer.core.BaritoneSession;
import com.xploits.travel.core.SafetyNet;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What restock may do with the player's Baritone values (restock spec 3 "Baritone", ruling P26), decided apart from the
 * files and the chat so each combination is proved by a unit test: an unrestored saved session is the source of the
 * values and is never saved over; nothing is saved without a prefix the safety net accepts; the file is deleted only
 * when it is certain that nothing of it is owed (nothing delivered and it is ours, or everything given back).
 */
public final class BaritoneSaveRules {
    private BaritoneSaveRules() {
    }

    /** The player's values, or the refusal when they cannot be known. */
    public record Values(Map<String, Boolean> player, Optional<RestockReason.Refusal> refusal) {
    }

    /**
     * @param pending       whether a saved session file exists
     * @param unrestored    that file as read (empty when it cannot be read)
     * @param settingsLines {@code baritone/settings.txt}'s lines: an empty list when the file is missing (Baritone's
     *                      defaults), an empty Optional when it cannot be read
     */
    public static Values playerValues(boolean pending, Optional<BaritoneSession.Saved> unrestored,
                                      Optional<List<String>> settingsLines) {
        if (pending) {
            Optional<BaritoneSession.Saved> usable = repairable(unrestored);
            if (usable.isEmpty()) return refused(RestockReason.BARITONE_SAVED_UNREADABLE, "");
            return new Values(usable.get().player(), Optional.empty());
        }
        if (settingsLines.isEmpty()) return refused(RestockReason.BARITONE_SETTINGS_UNREADABLE, "");
        Map<String, String> file = BaritoneSession.parse(settingsLines.get());
        Optional<String> bad = BaritoneValues.unreadable(file);
        if (bad.isPresent()) return refused(RestockReason.BARITONE_SETTINGS_UNREADABLE, bad.get());
        Optional<Map<String, Boolean>> values = BaritoneSession.playerValues(file);
        if (values.isEmpty()) return refused(RestockReason.BARITONE_SETTINGS_UNREADABLE, "");
        return new Values(values.get(), Optional.empty());
    }

    /** The prefix a session speaks with must be one the safety net accepts, checked before anything is saved. */
    public static Optional<RestockReason.Refusal> prefixRefusal(String prefix) {
        if (SafetyNet.prefixRejection(prefix) == null) return Optional.empty();
        return Optional.of(new RestockReason.Refusal(RestockReason.PREFIX_INVALID, ""));
    }

    /** The first {@code #set} is preceded by a save, unless an unrestored one is already the record. */
    public static boolean saveBeforeFirstSet(boolean pending) {
        return !pending;
    }

    /** After a begin whose preparation was not fully delivered: whether the file is not a debt (ours, nothing sent). */
    public static boolean deleteAfterFailedBegin(int delivered, boolean savedByThisBegin) {
        return delivered == 0 && savedByThisBegin;
    }

    /**
     * At a session's end and at the join repair: only a restoration whose every command (the {@code #cancel} included)
     * was delivered pays the debt. Counts, not a running boolean, so one lost command can never be forgotten; and
     * nothing sent proves nothing.
     */
    public static boolean deleteAfterRestoration(int delivered, int sent) {
        return sent > 0 && delivered == sent;
    }

    /** A saved session the repair may act on: readable and with a prefix the net accepts; else kept and warned. */
    public static Optional<BaritoneSession.Saved> repairable(Optional<BaritoneSession.Saved> read) {
        return read.filter(s -> SafetyNet.prefixRejection(s.prefix()) == null);
    }

    private static Values refused(RestockReason reason, String detail) {
        return new Values(Map.of(), Optional.of(new RestockReason.Refusal(reason, detail)));
    }
}
