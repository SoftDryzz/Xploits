package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * The profile parts of {@code .xploits pvp} (design, precise rules "Status command"): the active profile
 * as {@code profile <name>[*]}, and the modules the profile does not allow grouped into one
 * {@code off by profile: ...} line instead of one "not turned on" line each. Pure, so it is tested.
 */
public final class PvpStatus {
    private PvpStatus() {
    }

    /** Whether the director skipped this module only because the active profile does not allow it. */
    public static boolean isProfileOff(Skipped skipped) {
        return skipped.reason().key() == PvpText.PROFILE_OFF;
    }

    /** The skips that are real shortages or rules, in their order: what chat and the loud warning talk about. */
    public static List<Skipped> withoutProfileOff(List<Skipped> skipped) {
        return skipped.stream().filter(s -> !isProfileOff(s)).toList();
    }

    /** The names of the modules the active profile does not allow, in the director's order. */
    public static List<String> profileOffNames(List<Skipped> skipped) {
        List<String> names = new ArrayList<>();
        for (Skipped s : skipped) {
            if (isProfileOff(s)) names.add(s.module().name());
        }
        return names;
    }

    /** {@code  · profile <name>} with a trailing {@code *} when the live values differ from it. */
    public static Msg profile(String name, boolean modified) {
        return Msg.of(PvpText.STATUS_PROFILE, "name", name, "modified", modified ? "*" : "");
    }

    /**
     * One {@code STATUS_SKIPPED} line per skip that is not {@code PROFILE_OFF}, then a single {@code
     * STATUS_PROFILE_OFF} line naming every module the profile keeps off; {@code NOTHING} when there is
     * neither.
     */
    public static Msg skippedLines(List<Skipped> skipped) {
        Msg lines = null;
        for (Skipped s : withoutProfileOff(skipped)) {
            lines = append(lines, Msg.of(PvpText.STATUS_SKIPPED, "module", s.module().name(), "reason", s.reason()));
        }
        List<String> off = profileOffNames(skipped);
        if (!off.isEmpty()) {
            lines = append(lines, Msg.of(PvpText.STATUS_PROFILE_OFF, "modules", String.join(", ", off)));
        }
        return lines == null ? Msg.of(PvpText.NOTHING) : lines;
    }

    private static Msg append(Msg head, Msg line) {
        return head == null ? line : Msg.of(PvpText.CONCAT, "first", head, "second", line);
    }
}
