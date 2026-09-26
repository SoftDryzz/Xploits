package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * The profile parts of {@code .xploits pvp} (design, precise rules "Status command"): the active profile
 * as {@code profile <name>[*]}, and the modules the profile does not allow grouped into one
 * {@code off by profile: ...} line instead of one "not turned on" line each. The managed modules this
 * Meteor build does not have get the same treatment, on their own {@code missing in Meteor: ...} line.
 * Pure, so it is tested.
 */
public final class PvpStatus {
    private PvpStatus() {
    }

    /** Whether the director skipped this module only because the active profile does not allow it. */
    public static boolean isProfileOff(Skipped skipped) {
        return skipped.reason().key() == PvpText.PROFILE_OFF;
    }

    /** Whether the director skipped this module because this Meteor build does not have it. */
    public static boolean isMissing(Skipped skipped) {
        return skipped.reason().key() == PvpText.MODULE_MISSING_SKIP;
    }

    /**
     * The skips that are real shortages or rules, in their order: what chat and the loud warning talk
     * about. A profile choice and a module Meteor does not have are neither: the first is the player's
     * and the second is said once on turning auto-pvp on, and both would repeat in every fight.
     */
    public static List<Skipped> realSkips(List<Skipped> skipped) {
        return skipped.stream().filter(s -> !isProfileOff(s) && !isMissing(s)).toList();
    }

    /** The names of the modules the active profile does not allow, in the director's order. */
    public static List<String> profileOffNames(List<Skipped> skipped) {
        List<String> names = new ArrayList<>();
        for (Skipped s : skipped) {
            if (isProfileOff(s)) names.add(s.module().name());
        }
        return names;
    }

    /** The names of the modules this Meteor build does not have, in the director's order. */
    public static List<String> missingNames(List<Skipped> skipped) {
        List<String> names = new ArrayList<>();
        for (Skipped s : skipped) {
            if (isMissing(s)) names.add(s.module().name());
        }
        return names;
    }

    /** {@code  · profile <name>} with a trailing {@code *} when the live values differ from it. */
    public static Msg profile(String name, boolean modified) {
        return Msg.of(PvpText.STATUS_PROFILE, "name", name, "modified", modified ? "*" : "");
    }

    /**
     * One {@code STATUS_SKIPPED} line per {@linkplain #realSkips real skip}, then a single {@code
     * STATUS_PROFILE_OFF} line naming every module the profile keeps off and a single {@code
     * STATUS_MISSING} one naming every module Meteor does not have; {@code NOTHING} when there is none.
     */
    public static Msg skippedLines(List<Skipped> skipped) {
        Msg lines = null;
        for (Skipped s : realSkips(skipped)) {
            lines = append(lines, Msg.of(PvpText.STATUS_SKIPPED, "module", s.module().name(), "reason", s.reason()));
        }
        List<String> off = profileOffNames(skipped);
        if (!off.isEmpty()) {
            lines = append(lines, Msg.of(PvpText.STATUS_PROFILE_OFF, "modules", String.join(", ", off)));
        }
        List<String> missing = missingNames(skipped);
        if (!missing.isEmpty()) {
            lines = append(lines, Msg.of(PvpText.STATUS_MISSING, "modules", String.join(", ", missing)));
        }
        return lines == null ? Msg.of(PvpText.NOTHING) : lines;
    }

    private static Msg append(Msg head, Msg line) {
        return head == null ? line : Msg.of(PvpText.CONCAT, "first", head, "second", line);
    }
}
