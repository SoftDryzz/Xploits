package com.xploits.printer.core;

import com.xploits.travel.core.BaritoneScript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's Baritone values for one printer session (printer spec §7): read from {@code baritone/settings.txt} exactly
 * as Baritone reads it, the five settings the printer changes, what it sends to restore them, and the small file that
 * keeps them until the restoration reached Baritone.
 */
public final class BaritoneSession {
    private BaritoneSession() {
    }

    /** {@code baritone-settings}: restore the player's own values, or Baritone's defaults ({@code set reset}). */
    public enum Mode { MINE, DEFAULTS }

    /** The settings the printer changes, in the order it sets them: the censor pair before anything else. */
    public static final List<String> CHANGED = List.of("censorCoordinates", "censorRanCommands", "allowBreak",
        "allowPlace", "allowWaterBucketFall");

    /** Baritone 1.17.0's defaults ({@code baritone/e}, read in the jar). */
    public static final Map<String, Boolean> DEFAULTS = Map.of("allowBreak", true, "allowPlace", true,
        "allowWaterBucketFall", true, "censorCoordinates", false, "censorRanCommands", false);

    /** What a printer session sets: nothing broken or placed by Baritone, no water bucket, coordinates censored (M10). */
    public static final Map<String, Boolean> SESSION = Map.of("allowBreak", false, "allowPlace", false,
        "allowWaterBucketFall", false, "censorCoordinates", true, "censorRanCommands", true);

    private static final List<String> BEHAVIOUR = List.of("allowBreak", "allowPlace", "allowWaterBucketFall");
    private static final List<String> CENSOR = List.of("censorCoordinates", "censorRanCommands");
    private static final Pattern LINE = Pattern.compile("^(?<setting>[^ ]+) +(?<value>.+)");
    private static final String HEADER = "xploits-printer-baritone 1";

    /** {@code settings.txt} as Baritone reads it: lower-cased names → raw values. */
    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> m = new HashMap<>();
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) continue;
            m.put(matcher.group("setting").toLowerCase(Locale.ROOT), matcher.group("value"));
        }
        return m;
    }

    /** The player's own values of {@link #CHANGED}; empty when one present in the file is not a boolean. */
    public static Optional<Map<String, Boolean>> playerValues(Map<String, String> file) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String name : CHANGED) {
            String raw = file.get(name.toLowerCase(Locale.ROOT));
            if (raw == null) {
                m.put(name, DEFAULTS.get(name));
                continue;
            }
            Optional<Boolean> value = bool(raw);
            if (value.isEmpty()) return Optional.empty();
            m.put(name, value.get());
        }
        return Optional.of(m);
    }

    public static List<String> preparation(String prefix) {
        List<String> out = new ArrayList<>();
        for (String name : CHANGED) out.add(BaritoneScript.set(prefix, name, Boolean.toString(SESSION.get(name))));
        return List.copyOf(out);
    }

    public static List<String> restoration(Saved saved) {
        List<String> out = new ArrayList<>();
        for (String name : BEHAVIOUR) {
            out.add(saved.mode() == Mode.DEFAULTS ? BaritoneScript.reset(saved.prefix(), name)
                : BaritoneScript.set(saved.prefix(), name, Boolean.toString(saved.player().get(name))));
        }
        for (String name : CENSOR) {
            out.add(BaritoneScript.set(saved.prefix(), name, Boolean.toString(saved.player().get(name))));
        }
        return List.copyOf(out);
    }

    private static Optional<Boolean> bool(String raw) {
        // Baritone reads the raw value with Boolean.parseBoolean (no trim): "true  " is false for it, so a "true" with
        // trailing whitespace is refused rather than guessed; "false  " is false for it and for us.
        if (raw.equalsIgnoreCase("true")) return Optional.of(true);
        if (raw.strip().equalsIgnoreCase("false")) return Optional.of(false);
        return Optional.empty();
    }

    /** What the printer must give back, saved before its first {@code #set} (printer spec §7). */
    public record Saved(String prefix, Mode mode, Map<String, Boolean> player) {
        public Saved {
            if (prefix == null || prefix.isBlank()) throw new IllegalArgumentException("a saved session needs its prefix");
            if (!player.keySet().equals(new java.util.HashSet<>(CHANGED))) {
                throw new IllegalArgumentException("a saved session needs exactly the changed settings");
            }
            player = Map.copyOf(player);
        }

        public List<String> toLines() {
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            lines.add("prefix " + prefix);
            lines.add("mode " + mode.name());
            for (String name : CHANGED) lines.add(name + " " + player.get(name));
            return List.copyOf(lines);
        }

        public static Optional<Saved> fromLines(List<String> lines) {
            if (lines.size() != 3 + CHANGED.size() || !lines.get(0).equals(HEADER)) return Optional.empty();
            if (!lines.get(1).startsWith("prefix ")) return Optional.empty();
            String prefix = lines.get(1).substring("prefix ".length());
            if (prefix.isBlank() || !lines.get(2).startsWith("mode ")) return Optional.empty();
            Mode mode;
            try {
                mode = Mode.valueOf(lines.get(2).substring("mode ".length()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            Map<String, Boolean> player = new LinkedHashMap<>();
            for (int i = 0; i < CHANGED.size(); i++) {
                String expected = CHANGED.get(i) + " ";
                String line = lines.get(3 + i);
                if (!line.startsWith(expected)) return Optional.empty();
                String value = line.substring(expected.length());
                if (!value.equals("true") && !value.equals("false")) return Optional.empty();
                player.put(CHANGED.get(i), Boolean.parseBoolean(value));
            }
            return Optional.of(new Saved(prefix, mode, player));
        }
    }
}
