package com.xploits.console.core;

import com.xploits.shared.core.i18n.Catalog;

import java.util.List;
import java.util.StringJoiner;

/** The header's four data rows (console spec §9). Unknown is {@code ?}; never 0. */
public final class Header {
    /** The four rows' names, in row order, for the "hidden" part of the status line. */
    public static final List<WindowText> NAMES = List.of(
        WindowText.SECTION_ENVIRONMENT, WindowText.SECTION_FLIGHT, WindowText.SECTION_COMBAT, WindowText.SECTION_MODULES);
    private static final String UNKNOWN = "?";

    private Header() {
    }

    public static List<String> rows(GameSnapshot s, Glyphs glyphs, Catalog texts) {
        return List.of(environment(s, texts), flight(s, texts), combat(s, texts), modules(s, glyphs, texts));
    }

    private static String environment(GameSnapshot s, Catalog t) {
        String dimension = s.dimension() == null ? UNKNOWN : s.dimension().replaceFirst("^minecraft:", "");
        return t.render(WindowText.ENVIRONMENT_ROW, "dimension", dimension, "loaded", n(s.players()), "ours", n(s.friendly()));
    }

    private static String flight(GameSnapshot s, Catalog t) {
        String elytra = s.elytraPct() == null ? UNKNOWN
            : s.elytraPct() < 0 ? t.render(WindowText.ELYTRA_NONE) : s.elytraPct() + " %";
        String movement;
        if (s.travel() != null) {
            movement = t.render(WindowText.TRAVEL_PROGRESS, "current", s.travel().current(), "total", s.travel().total(),
                "remaining", s.travel().remaining());
        } else if (s.sweep() != null) {
            movement = t.render(WindowText.SWEEP_PROGRESS, "current", s.sweep().current(), "total", s.sweep().total(),
                "remaining", s.sweep().remaining());
        } else {
            movement = t.render(WindowText.NO_TRAVEL);
        }
        return t.render(WindowText.FLIGHT_ROW, "rockets", n(s.fireworks()), "elytra", elytra, "movement", movement);
    }

    private static String combat(GameSnapshot s, Catalog t) {
        // The catalog formats the number in its language ({health,1}): 18,5 in Spanish, 18.5 in English.
        Object health = s.health() == null ? UNKNOWN : s.health();
        return t.render(WindowText.COMBAT_ROW, "health", health, "armor", n(s.armor()), "obsidian", n(s.obsidian()),
            "crystals", n(s.crystals()), "webs", n(s.webs()), "anvils", n(s.anvils()));
    }

    private static String modules(GameSnapshot s, Glyphs glyphs, Catalog t) {
        if (s.modules().isEmpty()) return t.render(WindowText.NO_MODULES);
        StringJoiner sj = new StringJoiner("  ");
        for (GameSnapshot.ModuleStatus m : s.modules()) {
            String activity = m.active() && !m.activity().isEmpty() ? " " + TerminalText.sanitize(m.activity()) : "";
            sj.add((m.active() ? glyphs.active() : glyphs.inactive()) + " " + TerminalText.sanitize(m.name()) + activity);
        }
        return sj.toString();
    }

    private static String n(Integer value) {
        return value == null ? UNKNOWN : value.toString();
    }
}
