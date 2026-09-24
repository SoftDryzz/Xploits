package com.xploits.console.core;

import com.xploits.shared.core.i18n.Language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The snapshot behind the console's header (console spec §9). Every field is {@code null} when it is
 * not known: an unknown value is drawn as {@code ?}, never {@code 0}.
 *
 * <p>{@code elytraPct == -1} means the player is not wearing an elytra, which is not the same as not
 * knowing. The ammunition counts are <b>hotbar only</b>, which is what the modules auto-pvp drives use.
 */
public record GameSnapshot(
    String dimension,
    Integer players,
    Integer friendly,
    Integer fireworks,
    Integer elytraPct,
    Progress travel,
    Progress sweep,
    Double health,
    Integer armor,
    Integer obsidian,
    Integer crystals,
    Integer webs,
    Integer anvils,
    List<ModuleStatus> modules,
    Language language) {

    /** How far a travel or a sweep has got: {@code current} of {@code total}, and the blocks still to go. */
    public record Progress(int current, int total, long remaining) {
    }

    /** An Xploits module: whether it is on and what it is doing now, in a few words. */
    public record ModuleStatus(String name, boolean active, String activity) {
        public ModuleStatus {
            Objects.requireNonNull(name, "a module without a name");
            Objects.requireNonNull(activity, "an empty activity is \"\", not null");
        }
    }

    private static final String UNKNOWN = "-";
    private static final List<String> KEYS =
        List.of("dim", "ply", "frn", "fwk", "ely", "trv", "swp", "hp", "arm", "obs", "cry", "web", "anv", "mod", "lng");

    public GameSnapshot {
        modules = List.copyOf(Objects.requireNonNull(modules, "the module list may be empty, not null"));
        Objects.requireNonNull(language, "a snapshot says which language the window speaks");
    }

    /** No player in the world: everything unknown except the modules. */
    public static GameSnapshot withoutPlayer(List<ModuleStatus> modules, Language language) {
        return new GameSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null, modules, language);
    }

    /** The same snapshot with another module list: the sentinel uses it to hold back a suspicious activity. */
    public GameSnapshot withModules(List<ModuleStatus> others) {
        return new GameSnapshot(dimension, players, friendly, fireworks, elytraPct, travel, sweep, health, armor,
            obsidian, crystals, webs, anvils, others, language);
    }

    public String encode() {
        StringJoiner sj = new StringJoiner(";");
        sj.add("dim=" + (dimension == null ? UNKNOWN : Escape.escape(dimension)));
        sj.add("ply=" + number(players));
        sj.add("frn=" + number(friendly));
        sj.add("fwk=" + number(fireworks));
        sj.add("ely=" + number(elytraPct));
        sj.add("trv=" + progress(travel));
        sj.add("swp=" + progress(sweep));
        sj.add("hp=" + (health == null ? UNKNOWN : Double.toString(health)));
        sj.add("arm=" + number(armor));
        sj.add("obs=" + number(obsidian));
        sj.add("cry=" + number(crystals));
        sj.add("web=" + number(webs));
        sj.add("anv=" + number(anvils));
        StringJoiner mods = new StringJoiner("|");
        for (ModuleStatus m : modules) {
            mods.add(Escape.escape(m.name()) + "," + (m.active() ? "1" : "0") + "," + Escape.escape(m.activity()));
        }
        sj.add("mod=" + mods);
        sj.add("lng=" + language.code());
        return sj.toString();
    }

    public static GameSnapshot decode(String s) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : Escape.split(s, ';')) {
            List<String> kv = Escape.split(pair, '=');
            if (kv.size() != 2) throw new IllegalArgumentException("malformed pair in the snapshot");
            String key = kv.get(0);
            if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown key in the snapshot: " + key);
            if (values.put(key, kv.get(1)) != null) throw new IllegalArgumentException("repeated key in the snapshot: " + key);
        }
        for (String key : KEYS) {
            if (!values.containsKey(key)) throw new IllegalArgumentException("key " + key + " is missing from the snapshot");
        }
        return new GameSnapshot(
            text(values.get("dim")),
            integer(values.get("ply")),
            integer(values.get("frn")),
            integer(values.get("fwk")),
            integer(values.get("ely")),
            progress(values.get("trv")),
            progress(values.get("swp")),
            UNKNOWN.equals(values.get("hp")) ? null : Double.valueOf(values.get("hp")),
            integer(values.get("arm")),
            integer(values.get("obs")),
            integer(values.get("cry")),
            integer(values.get("web")),
            integer(values.get("anv")),
            modules(values.get("mod")),
            Language.fromCode(values.get("lng")).orElse(Language.EN));
    }

    private static String number(Integer n) {
        return n == null ? UNKNOWN : n.toString();
    }

    private static String progress(Progress p) {
        return p == null ? UNKNOWN : p.current() + "/" + p.total() + "/" + p.remaining();
    }

    private static String text(String v) {
        return UNKNOWN.equals(v) ? null : Escape.unescape(v);
    }

    private static Integer integer(String v) {
        return UNKNOWN.equals(v) ? null : Integer.valueOf(v);
    }

    private static Progress progress(String v) {
        if (UNKNOWN.equals(v)) return null;
        String[] parts = v.split("/", -1);
        if (parts.length != 3) throw new IllegalArgumentException("malformed progress: " + v);
        return new Progress(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
    }

    private static List<ModuleStatus> modules(String v) {
        if (v.isEmpty()) return List.of();
        List<ModuleStatus> list = new ArrayList<>();
        for (String piece : Escape.split(v, '|')) {
            List<String> fields = Escape.split(piece, ',');
            if (fields.size() != 3) throw new IllegalArgumentException("malformed module in the snapshot");
            boolean active = switch (fields.get(1)) {
                case "1" -> true;
                case "0" -> false;
                default -> throw new IllegalArgumentException("unknown module state: " + fields.get(1));
            };
            list.add(new ModuleStatus(Escape.unescape(fields.get(0)), active, Escape.unescape(fields.get(2))));
        }
        return list;
    }
}
