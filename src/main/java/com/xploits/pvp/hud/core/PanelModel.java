package com.xploits.pvp.hud.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.PvpText;
import com.xploits.pvp.core.Resource;
import com.xploits.shared.core.i18n.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns one {@link PanelInput} into the lines the {@code xploits-pvp} HUD element draws (spec §3). Pure
 * text: no rendering, no settings, no game access — the element only draws what {@link #lines} returns.
 */
public final class PanelModel {
    private PanelModel() {
    }

    /**
     * The panel for this frame, in order, each line present only when it has something to say.
     * Auto-pvp off collapses everything to the single {@link HudText#OFF} line.
     */
    public static List<PanelLine> lines(PanelInput in) {
        if (!in.autoPvpOn()) {
            return List.of(new PanelLine(Msg.of(HudText.OFF, "name", markedProfileName(in)), Tone.NORMAL));
        }

        List<PanelLine> lines = new ArrayList<>();
        danger(in).ifPresent(lines::add);
        lines.add(header(in));
        lines.add(target(in));
        modules(in, lines);
        resources(in, lines);
        fight(in, lines);
        return List.copyOf(lines);
    }

    /**
     * A fixed, fully populated panel for the HUD editor ({@code isInEditor()}), so the element has
     * something to size itself against before auto-pvp has ever run.
     */
    public static List<PanelLine> sample() {
        PanelInput sample = new PanelInput(
            true, "balanced", false,
            CombatState.SURFACE, CombatPosture.THREATENED,
            "Herobrine", 12.3,
            List.of("crystal-aura", "surround"),
            List.of("auto-trap"),
            List.of("auto-city", "auto-anvil"),
            List.of(new PanelInput.Idle("obsidian", List.of("hole-filler"))),
            3, 2, 5,
            true, false,
            new PanelInput.LiveFight(12, 1, 2, 18.5),
            true);
        return lines(sample);
    }

    // --- 1. danger -------------------------------------------------------------------------------

    /**
     * Priority, highest first, only one shown: (a) no totems in a fight, (b) OUT_OF_RESOURCES, (c) a
     * watched resource idle, (d) crystal-aura on without crystals.
     */
    private static Optional<PanelLine> danger(PanelInput in) {
        boolean inFight = in.state() != CombatState.NO_COMBAT;
        if (inFight && in.totems() == 0) {
            return Optional.of(new PanelLine(Msg.of(HudText.DANGER_NO_TOTEMS), Tone.DANGER));
        }
        if (in.outOfResources()) {
            return Optional.of(new PanelLine(Msg.of(PvpText.OUT_OF_RESOURCES_NONE), Tone.DANGER));
        }
        if (!in.idle().isEmpty()) {
            PanelInput.Idle first = in.idle().getFirst();
            Msg line = Msg.of(HudText.DANGER_IDLE, "material", material(first.resource()), "modules", join(first.modules()));
            return Optional.of(new PanelLine(line, Tone.DANGER));
        }
        if (in.crystalAuraEnabled() && in.crystals() == 0) {
            return Optional.of(new PanelLine(Msg.of(PvpText.AURA_NO_CRYSTALS), Tone.DANGER));
        }
        return Optional.empty();
    }

    // --- 2. profile / state / posture --------------------------------------------------------------

    /** Posture colours: CALM normal, THREATENED warn. */
    private static PanelLine header(PanelInput in) {
        Msg text = Msg.of(HudText.HEADER, "profile", markedProfileName(in), "state", PvpText.of(in.state()), "posture", PvpText.of(in.posture()));
        Tone tone = in.posture() == CombatPosture.THREATENED ? Tone.WARN : Tone.NORMAL;
        return new PanelLine(text, tone);
    }

    /** The active profile's name, with the trailing {@code *} the on-state panel uses for "modified" (line 2's
     *  header): the off line reuses the same mark instead of hiding that the live values would not match it. */
    private static String markedProfileName(PanelInput in) {
        return in.profileName() + (in.profileModified() ? "*" : "");
    }

    // --- 3. target ---------------------------------------------------------------------------------

    private static PanelLine target(PanelInput in) {
        if (in.target() == null) return new PanelLine(Msg.of(HudText.NO_TARGET), Tone.NORMAL);
        return new PanelLine(Msg.of(HudText.TARGET, "name", in.target(), "distance", in.targetDistance()), Tone.NORMAL);
    }

    // --- 4. modules ----------------------------------------------------------------------------------

    private static void modules(PanelInput in, List<PanelLine> lines) {
        if (!in.enabled().isEmpty()) {
            lines.add(new PanelLine(Msg.of(HudText.MODULES_ON, "modules", join(in.enabled())), Tone.GOOD));
        }
        if (!in.released().isEmpty()) {
            lines.add(new PanelLine(Msg.of(HudText.MODULES_RELEASED, "modules", join(in.released())), Tone.WARN));
        }
        if (!in.profileOff().isEmpty()) {
            lines.add(new PanelLine(Msg.of(HudText.MODULES_OFF_BY_PROFILE, "modules", join(in.profileOff())), Tone.MUTED));
        }
    }

    // --- 5. resources --------------------------------------------------------------------------------

    /**
     * Crystals need 1 when {@code crystal-aura} is enabled, obsidian needs the largest minimum among
     * the enabled modules that spend it, totems have no need beyond zero. Below need is WARN.
     */
    private static void resources(PanelInput in, List<PanelLine> lines) {
        int crystalsNeed = in.crystalAuraEnabled() ? 1 : 0;
        lines.add(new PanelLine(Msg.of(HudText.RESOURCE_CRYSTALS, "count", in.crystals()),
            in.crystals() < crystalsNeed ? Tone.WARN : Tone.NORMAL));

        lines.add(new PanelLine(Msg.of(HudText.RESOURCE_TOTEMS, "count", in.totems()),
            in.totems() == 0 ? Tone.WARN : Tone.NORMAL));

        int obsidianNeed = obsidianNeed(in.enabled());
        lines.add(new PanelLine(Msg.of(HudText.RESOURCE_OBSIDIAN, "count", in.obsidian()),
            in.obsidian() < obsidianNeed ? Tone.WARN : Tone.NORMAL));
    }

    /**
     * The obsidian minimum of the enabled module that needs the most of it, or 0 if none is enabled.
     * The consumers come from the catalog, so {@code anti-anvil} counts as the fourth one.
     */
    private static int obsidianNeed(List<String> enabled) {
        int need = 0;
        for (ManagedModule module : ManagedModules.ALL) {
            if (module.needs() != Resource.OBSIDIAN) continue;
            if (enabled.contains(module.name())) need = Math.max(need, module.minimum());
        }
        return need;
    }

    // --- 6. fight ------------------------------------------------------------------------------------

    private static void fight(PanelInput in, List<PanelLine> lines) {
        if (!in.showFight() || in.fight() == null) return;
        PanelInput.LiveFight f = in.fight();
        Msg text = Msg.of(HudText.FIGHT, "seconds", f.seconds(), "yourPops", f.yourPops(),
            "theirPops", f.theirPops(), "damageTaken", f.damageTaken());
        lines.add(new PanelLine(text, Tone.NORMAL));
    }

    // --- shared helpers --------------------------------------------------------------------------------

    /** What a resource is called to the player, reusing {@code PvpText}'s material words. */
    private static PvpText material(String resource) {
        return switch (resource) {
            case "crystals" -> PvpText.MATERIAL_CRYSTALS;
            case "obsidian" -> PvpText.MATERIAL_OBSIDIAN;
            case "webs" -> PvpText.MATERIAL_WEBS;
            case "anvils" -> PvpText.MATERIAL_ANVILS;
            case "string" -> PvpText.MATERIAL_STRING;
            case "slabs" -> PvpText.MATERIAL_SLABS;
            default -> PvpText.MATERIAL_OTHER;
        };
    }

    /** "a, b and c", listed the way the player's language does it; {@code names} is never empty here. */
    private static Object join(List<String> names) {
        Object head = names.getFirst();
        if (names.size() == 1) return head;
        for (String name : names.subList(1, names.size() - 1)) {
            head = Msg.of(HudText.JOIN_COMMA, "first", head, "rest", name);
        }
        return Msg.of(HudText.JOIN_AND, "first", head, "second", names.getLast());
    }
}
